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
package org.apache.pulsar.broker.service;

import static org.apache.bookkeeper.mledger.proto.MLDataFormats.ManagedLedgerInfo.LedgerInfo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.NotFoundException;
import lombok.Cleanup;
import org.apache.bookkeeper.mledger.ManagedCursor;
import org.apache.bookkeeper.mledger.impl.ManagedLedgerImpl;
import org.apache.pulsar.broker.service.persistent.PersistentTopic;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.common.api.proto.BrokerEntryMetadata;
import org.apache.pulsar.common.util.FutureUtil;
import org.assertj.core.api.ThrowableAssert;
import org.assertj.core.util.Sets;
import org.awaitility.Awaitility;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Test for the broker entry metadata.
 */
@Test(groups = "broker")
public class BrokerEntryMetadataE2ETest extends BrokerTestBase {
    private static final String BATCH_HEADER = "X-Pulsar-num-batch-message";
    private static final String BATCH_SIZE_HEADER = "X-Pulsar-batch-size";

    @DataProvider(name = "subscriptionTypes")
    public static Object[] subscriptionTypes() {
        return new Object[] {
                SubscriptionType.Exclusive,
                SubscriptionType.Failover,
                SubscriptionType.Shared,
                SubscriptionType.Key_Shared
        };
    }

    @BeforeClass
    protected void setup() throws Exception {
        conf.setBrokerEntryMetadataInterceptors(Sets.newTreeSet(
                "org.apache.pulsar.common.intercept.AppendBrokerTimestampMetadataInterceptor",
                "org.apache.pulsar.common.intercept.AppendIndexMetadataInterceptor"
                ));
        conf.setExposingBrokerEntryMetadataToClientEnabled(true);
        baseSetup();
    }

    @AfterClass(alwaysRun = true)
    protected void cleanup() throws Exception {
        internalCleanup();
    }

    @Test(dataProvider = "subscriptionTypes")
    public void testProduceAndConsume(SubscriptionType subType) throws Exception {
        final String topic = newTopicName();
        final int messages = 10;

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .create();

        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topic)
                .subscriptionType(subType)
                .subscriptionName("my-sub")
                .subscribe();

        for (int i = 0; i < messages; i++) {
            producer.send(String.valueOf(i).getBytes());
        }

        int receives = 0;
        for (int i = 0; i < messages; i++) {
            Message<byte[]> received = consumer.receive();
            ++receives;
            Assert.assertEquals(i, Integer.valueOf(new String(received.getValue())).intValue());
        }

        Assert.assertEquals(messages, receives);
    }

    @Test(timeOut = 20000)
    public void testPeekMessage() throws Exception {
        final String topic = newTopicName();
        final String subscription = "my-sub";
        final long eventTime = 200;
        final long deliverAtTime = 300;

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .create();

        long sendTime = System.currentTimeMillis();
        producer.newMessage()
                .eventTime(eventTime)
                .deliverAt(deliverAtTime)
                .value("hello".getBytes())
                .send();

        admin.topics().createSubscription(topic, subscription, MessageId.earliest);
        final List<Message<byte[]>> messages = admin.topics().peekMessages(topic, subscription, 1);
        Assert.assertEquals(messages.size(), 1);
        MessageImpl message = (MessageImpl) messages.get(0);
        Assert.assertEquals(message.getData(), "hello".getBytes());
        Assert.assertEquals(message.getEventTime(), eventTime);
        Assert.assertEquals(message.getDeliverAtTime(), deliverAtTime);
        Assert.assertTrue(message.getPublishTime() >= sendTime);

        BrokerEntryMetadata entryMetadata = message.getBrokerEntryMetadata();
        Assert.assertEquals(entryMetadata.getIndex(), 0);
        Assert.assertTrue(entryMetadata.getBrokerTimestamp() >= sendTime);
    }

    @Test(timeOut = 20000)
    public void testGetMessageById() throws Exception {
        final String topic = newTopicName();
        final String subscription = "my-sub";
        final long eventTime = 200;
        final long deliverAtTime = 300;

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .create();

        long sendTime = System.currentTimeMillis();
        MessageIdImpl messageId = (MessageIdImpl) producer.newMessage()
                .eventTime(eventTime)
                .deliverAt(deliverAtTime)
                .value("hello".getBytes())
                .send();

        admin.topics().createSubscription(topic, subscription, MessageId.earliest);
        MessageImpl message = (MessageImpl) admin.topics()
                .getMessageById(topic, messageId.getLedgerId(), messageId.getEntryId());
        Assert.assertEquals(message.getData(), "hello".getBytes());
        Assert.assertEquals(message.getEventTime(), eventTime);
        Assert.assertEquals(message.getDeliverAtTime(), deliverAtTime);
        Assert.assertTrue(message.getPublishTime() >= sendTime);

        BrokerEntryMetadata entryMetadata = message.getBrokerEntryMetadata();
        Assert.assertEquals(entryMetadata.getIndex(), 0);
        Assert.assertTrue(entryMetadata.getBrokerTimestamp() >= sendTime);
    }


    @Test(timeOut = 20000)
    public void testExamineMessage() throws Exception {
        final String topic = newTopicName();
        final String subscription = "my-sub";
        final long eventTime = 200;
        final long deliverAtTime = 300;

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .create();

        long sendTime = System.currentTimeMillis();
        producer.newMessage()
                .eventTime(eventTime)
                .deliverAt(deliverAtTime)
                .value("hello".getBytes())
                .send();

        admin.topics().createSubscription(topic, subscription, MessageId.earliest);
        MessageImpl message =
                (MessageImpl) admin.topics().examineMessage(topic, "earliest", 1);
        Assert.assertEquals(message.getData(), "hello".getBytes());
        Assert.assertEquals(message.getEventTime(), eventTime);
        Assert.assertEquals(message.getDeliverAtTime(), deliverAtTime);
        Assert.assertTrue(message.getPublishTime() >= sendTime);

        BrokerEntryMetadata entryMetadata = message.getBrokerEntryMetadata();
        Assert.assertEquals(entryMetadata.getIndex(), 0);
        Assert.assertTrue(entryMetadata.getBrokerTimestamp() >= sendTime);
    }

    @Test(timeOut = 20000)
    public void testBatchMessage() throws Exception {
        final String topic = newTopicName();
        final String subscription = "my-sub";
        final long eventTime = 200;
        final int msgNum = 2;

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                 // make sure 2 messages in one batch, because if only one message in batch,
                 // producer will not send batched messages
                .batchingMaxPublishDelay(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
                .batchingMaxMessages(msgNum)
                .batchingMaxBytes(Integer.MAX_VALUE)
                .enableBatching(true)
                .create();

        long sendTime = System.currentTimeMillis();
        // send message which is batch message, so do not set the deliverAtTime
        List<CompletableFuture<MessageId>> messageIdsFuture = new ArrayList<>(msgNum);
        for (int i = 0; i < msgNum; ++i) {
            CompletableFuture<MessageId> messageId = producer.newMessage()
                .eventTime(eventTime)
                .value(("hello" + i).getBytes())
                .sendAsync();
            messageIdsFuture.add(messageId);
        }
        FutureUtil.waitForAll(messageIdsFuture);

        // 1. test for peekMessages
        admin.topics().createSubscription(topic, subscription, MessageId.earliest);
        final List<Message<byte[]>> messages = admin.topics().peekMessages(topic, subscription, msgNum);
        Assert.assertEquals(messages.size(), msgNum);

        MessageImpl message;
        BrokerEntryMetadata entryMetadata;
        for (int i = 0; i < msgNum; ++i) {
            message = (MessageImpl) messages.get(i);
            Assert.assertEquals(message.getData(), ("hello" + i).getBytes());
            Assert.assertTrue(message.getPublishTime() >= sendTime);
            entryMetadata = message.getBrokerEntryMetadata();
            Assert.assertTrue(entryMetadata.getBrokerTimestamp() >= sendTime);
            Assert.assertEquals(entryMetadata.getIndex(), msgNum - 1);
            System.out.println(message.getProperties());
            Assert.assertEquals(Integer.parseInt(message.getProperty(BATCH_HEADER)), msgNum);
            // make sure BATCH_SIZE_HEADER > 0
            Assert.assertTrue(Integer.parseInt(message.getProperty(BATCH_SIZE_HEADER)) > 0);
        }

        // getMessagesById and examineMessage only return the first messages in the batch
        // 2. test for getMessagesById
        MessageIdImpl messageId = (MessageIdImpl) messageIdsFuture.get(0).get();
        message = (MessageImpl) admin.topics().getMessageById(topic, messageId.getLedgerId(), messageId.getEntryId());
        // getMessagesById return the first message in the batch
        Assert.assertEquals(message.getData(), ("hello" + 0).getBytes());
        Assert.assertTrue(message.getPublishTime() >= sendTime);
        entryMetadata = message.getBrokerEntryMetadata();
        Assert.assertTrue(entryMetadata.getBrokerTimestamp() >= sendTime);
        Assert.assertEquals(entryMetadata.getIndex(), msgNum - 1);
        System.out.println(message.getProperties());
        Assert.assertEquals(Integer.parseInt(message.getProperty(BATCH_HEADER)), msgNum);
        // make sure BATCH_SIZE_HEADER > 0
        Assert.assertTrue(Integer.parseInt(message.getProperty(BATCH_SIZE_HEADER)) > 0);

        // 3. test for examineMessage
        message = (MessageImpl) admin.topics().examineMessage(topic, "earliest", 1);
        Assert.assertEquals(message.getData(), ("hello" + 0).getBytes());
        Assert.assertTrue(message.getPublishTime() >= sendTime);
        entryMetadata = message.getBrokerEntryMetadata();
        Assert.assertTrue(entryMetadata.getBrokerTimestamp() >= sendTime);
        Assert.assertEquals(entryMetadata.getIndex(), msgNum - 1);
        System.out.println(message.getProperties());
        Assert.assertEquals(Integer.parseInt(message.getProperty(BATCH_HEADER)), msgNum);
        // make sure BATCH_SIZE_HEADER > 0
        Assert.assertTrue(Integer.parseInt(message.getProperty(BATCH_SIZE_HEADER)) > 0);
    }

    @Test(timeOut = 20000)
    public void testGetLastMessageId() throws Exception {
        final String topic = "persistent://prop/ns-abc/topic-test";
        final String subscription = "my-sub";

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .create();
        producer.newMessage().value("hello".getBytes()).send();

        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topic)
                .subscriptionType(SubscriptionType.Exclusive)
                .subscriptionName(subscription)
                .subscribe();
    }

    @Test(timeOut = 20000)
    public void testConsumerGetBrokerEntryMetadataForIndividualMessage() throws Exception {
        final String topic = newTopicName();
        final String subscription = "my-sub";

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .enableBatching(false)
                .create();
        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topic)
                .subscriptionType(SubscriptionType.Exclusive)
                .subscriptionName(subscription)
                .subscribe();

        long sendTime = System.currentTimeMillis();

        final int messages = 10;
        for (int i = 0; i < messages; i++) {
            producer.send(String.valueOf(i).getBytes());
        }

        for (int i = 0; i < messages; i++) {
            Message<byte[]> received = consumer.receive();
            Assert.assertTrue(
                    received.hasBrokerPublishTime() && received.getBrokerPublishTime().orElse(-1L) >= sendTime);
            Assert.assertTrue(received.hasIndex() && received.getIndex().orElse(-1L) == i);
        }

        producer.close();
        consumer.close();
    }

    @Test(timeOut = 20000)
    public void testConsumerGetBrokerEntryMetadataForBatchMessage() throws Exception {
        final String topic = newTopicName();
        final String subscription = "my-sub";

        @Cleanup
        Producer<byte[]> producer = pulsarClient.newProducer()
                .topic(topic)
                .enableBatching(true)
                .batchingMaxPublishDelay(1, TimeUnit.MINUTES)
                .create();
        @Cleanup
        Consumer<byte[]> consumer = pulsarClient.newConsumer()
                .topic(topic)
                .subscriptionType(SubscriptionType.Exclusive)
                .subscriptionName(subscription)
                .subscribe();

        long sendTime = System.currentTimeMillis();

        int numOfMessages;
        // batch 1
        for (numOfMessages = 0; numOfMessages < 5; numOfMessages++) {
            producer.sendAsync(String.valueOf(numOfMessages).getBytes());
        }
        producer.flush();
        // batch 2
        for (; numOfMessages < 10; numOfMessages++) {
            producer.sendAsync(String.valueOf(numOfMessages).getBytes());
        }
        producer.flush();

        for (int i = 0; i < numOfMessages; i++) {
            Message<byte[]> received = consumer.receive();
            Assert.assertTrue(
                    received.hasBrokerPublishTime() && received.getBrokerPublishTime().orElse(-1L) >= sendTime);
            Assert.assertTrue(received.hasIndex() && received.getIndex().orElse(-1L) == i);
        }

        producer.close();
        consumer.close();
    }

    @Test
    public void testManagedLedgerTotalSize() throws Exception {
        final String topic = newTopicName();
        final int messages = 10;

        admin.topics().createNonPartitionedTopic(topic);
        admin.lookups().lookupTopic(topic);
        final ManagedLedgerImpl managedLedger = pulsar.getBrokerService().getTopicIfExists(topic).get()
                .map(topicObject -> (ManagedLedgerImpl) ((PersistentTopic) topicObject).getManagedLedger())
                .orElse(null);
        Assert.assertNotNull(managedLedger);
        final ManagedCursor cursor = managedLedger.openCursor("cursor"); // prevent ledgers being removed

        @Cleanup
        final Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topic)
                .create();
        for (int i = 0; i < messages; i++) {
            producer.send("msg-" + i);
        }

        Assert.assertTrue(managedLedger.getTotalSize() > 0);

        managedLedger.getConfig().setMinimumRolloverTime(0, TimeUnit.MILLISECONDS);
        managedLedger.getConfig().setMaxEntriesPerLedger(1);
        managedLedger.rollCurrentLedgerIfFull();

        Awaitility.await().atMost(Duration.ofSeconds(3))
                .until(() -> managedLedger.getLedgersInfo().size() > 1);

        final List<LedgerInfo> ledgerInfoList = managedLedger.getLedgersInfoAsList();
        Assert.assertEquals(ledgerInfoList.size(), 2);
        Assert.assertEquals(ledgerInfoList.get(0).getSize(), managedLedger.getTotalSize());

        cursor.close();
    }

    @Test
    public void testGetMessageIdByIndex() throws Exception {
        // 1. test no partitioned topic
        final String topicName = newTopicName();
        admin.topics().createNonPartitionedTopic(topicName);
        @Cleanup
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topicName)
                .enableBatching(false)
                .create();
        MessageIdImpl messageId = (MessageIdImpl) producer.send("test");
        Message<byte[]>
                message =
                admin.topics().getMessagesById(topicName, messageId.getLedgerId(), messageId.getEntryId()).get(0);
        long index = message.getIndex().get();
        MessageIdImpl messageIdByIndex = (MessageIdImpl) admin.topics().getMessageIdByIndex(topicName, index);
        Assert.assertEquals(messageIdByIndex, messageId);

        // 2. test partitioned topic
        final String topicName2 = newTopicName();
        final String partitionedTopicName = topicName2 + "-partition-" + 0;
        admin.topics().createPartitionedTopic(topicName2, 10);
        @Cleanup
        Producer<String> producer2 = pulsarClient.newProducer(Schema.STRING)
                .topic(topicName2)
                .enableBatching(false)
                .create();

        MessageIdImpl messageId2 = null;
        for (int i = 0; i < 200; i++) {
            messageId2 = (MessageIdImpl) producer2.send("test" + i);
            if (messageId2.getPartitionIndex() == 0) {
                break;
            }
        }
        Message<byte[]>
                message2 = admin.topics().getMessagesById(partitionedTopicName,
                messageId2.getLedgerId(), messageId2.getEntryId()).get(0);
        long index2 = message2.getIndex().get();
        // 2.1 test partitioned topic name with partition index
        MessageIdImpl messageIdByIndex2 =
                (MessageIdImpl) admin.topics().getMessageIdByIndex(partitionedTopicName, index2);
        Assert.assertEquals(messageIdByIndex2, messageId2);
        // 2.2 test partitioned topic name without partition index
        assertThrowsWithCause(() -> admin.topics().getMessageIdByIndex(topicName2, index2),
                PulsarAdminException.class, NotAllowedException.class);

        // 3. test invalid index
        assertThrowsWithCause(() -> admin.topics().getMessageIdByIndex(topicName, -1),
                PulsarAdminException.class, NotFoundException.class);

        assertThrowsWithCause(() -> admin.topics().getMessageIdByIndex(topicName, 100000),
                PulsarAdminException.class, NotFoundException.class);
    }

    @Test
    public void testGetMessageIdByIndexForEmptyTopic() throws PulsarAdminException {
        final String topicName = newTopicName();
        admin.topics().createNonPartitionedTopic(topicName);

        assertThrowsWithCause(() -> admin.topics().getMessageIdByIndex(topicName, 0),
                PulsarAdminException.class, NotFoundException.class);
    }

    @Test
    public void testGetMessageIdByIndexOutOfIndex() throws PulsarAdminException, PulsarClientException {
        final String topicName = newTopicName();
        admin.topics().createNonPartitionedTopic(topicName);
        @Cleanup
        final Producer<String> producer = pulsarClient.newProducer(Schema.STRING)
                .topic(topicName)
                .create();
        for (int i = 0; i < 100; i++) {
            producer.send("msg-" + i);
        }

        assertThrowsWithCause(() -> admin.topics().getMessageIdByIndex(topicName, 1000),
                PulsarAdminException.class, NotFoundException.class);
    }

    private void assertThrowsWithCause(ThrowableAssert.ThrowingCallable executable,
                                       Class<? extends Throwable> expectedException,
                                       Class<? extends Throwable> expectedCause) {
        assertThatThrownBy(executable)
                .isInstanceOf(expectedException)
                .hasRootCauseInstanceOf(expectedCause);
    }

}
