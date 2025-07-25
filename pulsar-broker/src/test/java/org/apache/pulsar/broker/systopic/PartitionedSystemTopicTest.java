/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.systopic;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import com.google.common.collect.Sets;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.LedgerOffloader;
import org.apache.bookkeeper.mledger.ManagedLedgerConfig;
import org.apache.bookkeeper.mledger.impl.NullLedgerOffloader;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.pulsar.broker.namespace.NamespaceService;
import org.apache.pulsar.broker.service.BrokerTestBase;
import org.apache.pulsar.broker.service.HealthChecker;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.service.TopicPolicyTestUtils;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.broker.service.schema.SchemaRegistry;
import org.apache.pulsar.client.admin.ListTopicsOptions;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.events.PulsarEvent;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.SystemTopicNames;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.naming.TopicVersion;
import org.apache.pulsar.common.policies.data.BacklogQuota;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.TenantInfoImpl;
import org.apache.pulsar.common.policies.data.TopicPolicies;
import org.apache.pulsar.common.policies.data.TopicType;
import org.apache.pulsar.common.util.FutureUtil;
import org.awaitility.Awaitility;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test(groups = "broker")
public class PartitionedSystemTopicTest extends BrokerTestBase {

    static final int PARTITIONS = 5;

    @BeforeMethod
    @Override
    protected void setup() throws Exception {
        conf.setAllowAutoTopicCreation(false);
        conf.setAllowAutoTopicCreationType(TopicType.PARTITIONED);
        conf.setDefaultNumPartitions(PARTITIONS);
        conf.setManagedLedgerMaxEntriesPerLedger(1);
        conf.setBrokerDeleteInactiveTopicsEnabled(false);
        conf.setTransactionCoordinatorEnabled(true);

        super.baseSetup();
    }

    @AfterMethod(alwaysRun = true)
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testAutoCreatedPartitionedSystemTopic() throws Exception {
        final String ns = "prop/ns-test";
        admin.namespaces().createNamespace(ns, 2);
        NamespaceEventsSystemTopicFactory systemTopicFactory = new NamespaceEventsSystemTopicFactory(pulsarClient);
        TopicPoliciesSystemTopicClient systemTopicClientForNamespace = systemTopicFactory
                .createTopicPoliciesSystemTopicClient(NamespaceName.get(ns));
        SystemTopicClient.Reader reader = systemTopicClientForNamespace.newReader();

        int partitions = admin.topics().getPartitionedTopicMetadata(
                String.format("persistent://%s/%s", ns, SystemTopicNames.NAMESPACE_EVENTS_LOCAL_NAME)).partitions;
        List<String> partitionedTopicList = admin.topics().getPartitionedTopicList(ns,
                ListTopicsOptions.builder().includeSystemTopic(true).build());
        Assert.assertEquals(partitionedTopicList.size(), 1);
        Assert.assertEquals(partitions, PARTITIONS);
        Assert.assertEquals(admin.topics().getList(ns, null,
                ListTopicsOptions.builder().includeSystemTopic(true).build()).size(), PARTITIONS);
        reader.close();
    }

    @Test(timeOut = 1000 * 60)
    public void testConsumerCreationWhenEnablingTopicPolicy() throws Exception {
        String tenant = "tenant-" + RandomStringUtils.randomAlphabetic(4).toLowerCase();
        admin.tenants().createTenant(tenant, new TenantInfoImpl(new HashSet<>(), Sets.newHashSet("test")));
        int namespaceCount = 30;
        for (int i = 0; i < namespaceCount; i++) {
            String ns = tenant + "/ns-" + i;
            admin.namespaces().createNamespace(ns, 4);
            String topic = ns + "/t1";
            admin.topics().createPartitionedTopic(topic, 2);
        }

        List<CompletableFuture<Consumer<byte[]>>> futureList = new ArrayList<>();
        for (int i = 0; i < namespaceCount; i++) {
            String topic = tenant + "/ns-" + i + "/t1";
            futureList.add(pulsarClient.newConsumer()
                    .topic(topic)
                    .subscriptionName("sub")
                    .subscribeAsync());
        }
        FutureUtil.waitForAll(futureList).get();
        // Close all the consumers after check
        for (CompletableFuture<Consumer<byte[]>> consumer : futureList) {
            consumer.join().close();
        }
    }

    @Test
    public void testProduceAndConsumeUnderSystemNamespace() throws Exception {
        TenantInfo tenantInfo = TenantInfo
                .builder()
                .adminRoles(Sets.newHashSet("admin"))
                .allowedClusters(Sets.newHashSet("test"))
                .build();
        admin.tenants().createTenant("pulsar", tenantInfo);
        admin.namespaces().createNamespace("pulsar/system", 2);
        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer().topic("pulsar/system/__topic-1").create();
        producer.send("test".getBytes(StandardCharsets.UTF_8));
        @Cleanup
        Consumer<byte[]> consumer = pulsarClient
                .newConsumer()
                .topic("pulsar/system/__topic-1")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscriptionName("sub1")
                .subscriptionType(SubscriptionType.Shared)
                .subscribe();
        Message<byte[]> receive = consumer.receive(5, TimeUnit.SECONDS);
        Assert.assertNotNull(receive);
    }

    @Test
    public void testHealthCheckTopicNotOffload() throws Exception {
        NamespaceName namespaceName = NamespaceService.getHeartbeatNamespaceV2(pulsar.getBrokerId(),
                pulsar.getConfig());
        TopicName topicName = TopicName.get("persistent", namespaceName, HealthChecker.HEALTH_CHECK_TOPIC_SUFFIX);
        PersistentTopic persistentTopic = (PersistentTopic) pulsar.getBrokerService()
                .getTopic(topicName.toString(), true).get().get();
        ManagedLedgerConfig config = persistentTopic.getManagedLedger().getConfig();
        config.setLedgerOffloader(NullLedgerOffloader.INSTANCE);
        admin.brokers().healthcheck(TopicVersion.V2);
        admin.topics().triggerOffload(topicName.toString(), MessageId.earliest);
        Awaitility.await().untilAsserted(() -> {
            Assert.assertEquals(persistentTopic.getManagedLedger().getOffloadedSize(), 0);
        });
        LedgerOffloader ledgerOffloader = Mockito.mock(LedgerOffloader.class);
        config.setLedgerOffloader(ledgerOffloader);
        Assert.assertEquals(config.getLedgerOffloader(), ledgerOffloader);
    }

    @Test
    public void testSystemNamespaceNotCreateChangeEventsTopic() throws Exception {
        admin.brokers().healthcheck(TopicVersion.V2);
        NamespaceName namespaceName = NamespaceService.getHeartbeatNamespaceV2(pulsar.getBrokerId(),
                pulsar.getConfig());
        TopicName topicName = TopicName.get("persistent", namespaceName, SystemTopicNames.NAMESPACE_EVENTS_LOCAL_NAME);
        Optional<Topic> optionalTopic = pulsar.getBrokerService()
                .getTopic(topicName.getPartition(1).toString(), false).join();
        Assert.assertTrue(optionalTopic.isEmpty());

        TopicName heartbeatTopicName = TopicName.get("persistent",
                namespaceName, HealthChecker.HEALTH_CHECK_TOPIC_SUFFIX);
        admin.topics().getRetention(heartbeatTopicName.toString());
        optionalTopic = pulsar.getBrokerService()
                .getTopic(topicName.getPartition(1).toString(), false).join();
        Assert.assertTrue(optionalTopic.isEmpty());
    }

    @Test
    public void testHeartbeatTopicNotAllowedToSendEvent() throws Exception {
        admin.brokers().healthcheck(TopicVersion.V2);
        NamespaceName namespaceName = NamespaceService.getHeartbeatNamespaceV2(pulsar.getBrokerId(),
                pulsar.getConfig());
        TopicName topicName = TopicName.get("persistent", namespaceName, SystemTopicNames.NAMESPACE_EVENTS_LOCAL_NAME);
        for (int partition = 0; partition < PARTITIONS; partition++) {
            pulsar.getBrokerService()
                    .getTopic(topicName.getPartition(partition).toString(), true).join();
        }
        Assert.assertThrows(PulsarAdminException.ConflictException.class, () -> {
            admin.topicPolicies().setMaxConsumers(topicName.toString(), 2);
        });
    }

    @Test
    public void testHeartbeatTopicBeDeleted() throws Exception {
        admin.brokers().healthcheck(TopicVersion.V2);
        NamespaceName namespaceName = NamespaceService.getHeartbeatNamespaceV2(pulsar.getBrokerId(),
                pulsar.getConfig());
        TopicName heartbeatTopicName = TopicName.get("persistent", namespaceName,
                HealthChecker.HEALTH_CHECK_TOPIC_SUFFIX);

        List<String> topics = getPulsar().getNamespaceService().getListOfPersistentTopics(namespaceName).join();
        Assert.assertEquals(topics.size(), 1);
        Assert.assertEquals(topics.get(0), heartbeatTopicName.toString());

        admin.topics().delete(heartbeatTopicName.toString(), true);
        topics = getPulsar().getNamespaceService().getListOfPersistentTopics(namespaceName).join();
        Assert.assertEquals(topics.size(), 0);
    }

    @Test
    public void testHeartbeatNamespaceNotCreateTransactionInternalTopic() throws Exception {
        admin.brokers().healthcheck(TopicVersion.V2);
        NamespaceName namespaceName = NamespaceService.getHeartbeatNamespaceV2(pulsar.getBrokerId(),
                pulsar.getConfig());
        TopicName topicName = TopicName.get("persistent",
                namespaceName, SystemTopicNames.TRANSACTION_BUFFER_SNAPSHOT);
        Optional<Topic> optionalTopic = pulsar.getBrokerService()
                .getTopic(topicName.getPartition(1).toString(), false).join();
        Assert.assertTrue(optionalTopic.isEmpty());

        List<String> topics = getPulsar().getNamespaceService().getListOfPersistentTopics(namespaceName).join();
        Assert.assertEquals(topics.size(), 1);
        TopicName heartbeatTopicName = TopicName.get("persistent",
                namespaceName, HealthChecker.HEALTH_CHECK_TOPIC_SUFFIX);
        Assert.assertEquals(topics.get(0), heartbeatTopicName.toString());
    }

    @Test
    public void testSetBacklogCausedCreatingProducerFailure() throws Exception {
        final String ns = "prop/ns-test";
        final String topic = ns + "/topic-1";

        admin.namespaces().createNamespace(ns, 2);
        admin.topics().createPartitionedTopic(String.format("persistent://%s", topic), 1);
        BacklogQuota quota = BacklogQuota.builder()
                .limitTime(2)
                .limitSize(-1)
                .retentionPolicy(BacklogQuota.RetentionPolicy.producer_exception)
                .build();
        admin.namespaces().setBacklogQuota(ns, quota, BacklogQuota.BacklogQuotaType.message_age);

        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .create();

        String partition0 = TopicName.get(String.format("persistent://%s", topic)).getPartition(0).toString();
        Optional<Topic> topicReference = pulsar.getBrokerService().getTopicReference(partition0);
        Assert.assertTrue(topicReference.isPresent());
        PersistentTopic persistentTopic = (PersistentTopic) topicReference.get();
        ManagedLedgerConfig config = persistentTopic.getManagedLedger().getConfig();
        config.setMinimumRolloverTime(1, TimeUnit.SECONDS);
        config.setMaximumRolloverTime(1, TimeUnit.SECONDS);
        persistentTopic.getManagedLedger().setConfig(config);
        MethodUtils.invokeMethod(
                persistentTopic.getManagedLedger(),
                true,
                "updateLastLedgerCreatedTimeAndScheduleRolloverTask");
        String msg1 = "msg-1";
        producer.send(msg1);
        Thread.sleep(3 * 1000);

        Consumer<String> consumer2 = pulsarClient.newConsumer(Schema.STRING)
                .topic(topic)
                .subscriptionName("sub-1")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscriptionType(SubscriptionType.Key_Shared)
                .subscribe();

        Message<String> receive = consumer2.receive();
        consumer2.acknowledge(receive);

        Thread.sleep(3 * 1000);

        try {
            @Cleanup
            PulsarClient pulsarClient1 = PulsarClient.builder()
                    .maxBackoffInterval(3, TimeUnit.SECONDS)
                    .operationTimeout(5, TimeUnit.SECONDS)
                    .serviceUrl(lookupUrl.toString()).connectionTimeout(2, TimeUnit.SECONDS).build();
            Producer<String> producerN = pulsarClient1
                    .newProducer(Schema.STRING).topic(topic).sendTimeout(3, TimeUnit.SECONDS).create();
            Assert.assertTrue(producerN.isConnected());
            producerN.close();
        } catch (Exception ex) {
            Assert.fail("failed to create producer");
        }
    }

    @Test
    public void testSystemTopicNotCheckExceed() throws Exception {
        final String ns = "prop/ns-test";
        final String topic = ns + "/topic-1";

        admin.namespaces().createNamespace(ns, 2);
        admin.topics().createPartitionedTopic(String.format("persistent://%s", topic), 1);

        conf.setMaxSameAddressConsumersPerTopic(1);
        admin.namespaces().setMaxConsumersPerTopic(ns, 1);
        admin.topicPolicies().setMaxConsumers(topic, 1);
        NamespaceEventsSystemTopicFactory systemTopicFactory = new NamespaceEventsSystemTopicFactory(pulsarClient);
        TopicPoliciesSystemTopicClient systemTopicClientForNamespace = systemTopicFactory
                .createTopicPoliciesSystemTopicClient(NamespaceName.get(ns));
        SystemTopicClient.Reader reader1 = systemTopicClientForNamespace.newReader();
        SystemTopicClient.Reader reader2 = systemTopicClientForNamespace.newReader();

        conf.setMaxSameAddressProducersPerTopic(1);
        admin.namespaces().setMaxProducersPerTopic(ns, 1);
        admin.topicPolicies().setMaxProducers(topic, 1);
        CompletableFuture<SystemTopicClient.Writer<PulsarEvent>> writer1 =
                systemTopicClientForNamespace.newWriterAsync();
        CompletableFuture<SystemTopicClient.Writer<PulsarEvent>> writer2 =
                systemTopicClientForNamespace.newWriterAsync();
        CompletableFuture<Void> f1 = admin.topicPolicies().setCompactionThresholdAsync(topic, 1L);

        FutureUtil.waitForAll(List.of(writer1, writer2, f1)).join();
        Assert.assertTrue(reader1.hasMoreEvents());
        Message<?> message = reader1.readNext();
        Assert.assertNotNull(message);
        message.release();
        Assert.assertTrue(reader2.hasMoreEvents());
        message = reader2.readNext();
        Assert.assertNotNull(reader2.readNext());
        message.release();
        reader1.close();
        reader2.close();
        writer1.get().close();
        writer2.get().close();
    }

    @Test
    public void testDeleteTopicSchemaAndPolicyWhenTopicIsNotLoaded() throws Exception {
        final String ns = "prop/ns-test";
        admin.namespaces().createNamespace(ns, 2);
        final String topicName = "persistent://prop/ns-test/testDeleteTopicSchemaAndPolicyWhenTopicIsNotLoaded";
        admin.topics().createNonPartitionedTopic(topicName);
        pulsarClient.newProducer(Schema.STRING).topic(topicName).create().close();
        admin.topicPolicies().setMaxConsumers(topicName, 2);
        Awaitility.await().untilAsserted(() -> assertEquals(admin.topicPolicies().getMaxConsumers(topicName), 2));
        CompletableFuture<Optional<Topic>> topic = pulsar.getBrokerService().getTopic(topicName, false);
        PersistentTopic persistentTopic = (PersistentTopic) topic.join().get();
        persistentTopic.close();
        admin.topics().delete(topicName);
        TopicPolicies topicPolicies = TopicPolicyTestUtils.getTopicPolicies(pulsar.getTopicPoliciesService(),
                TopicName.get(topicName));
        assertNull(topicPolicies);
        String base = TopicName.get(topicName).getPartitionedTopicName();
        String id = TopicName.get(base).getSchemaName();
        CompletableFuture<SchemaRegistry.SchemaAndMetadata> schema = pulsar.getSchemaRegistryService().getSchema(id);
        assertNull(schema.join());
    }
}
