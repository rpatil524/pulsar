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
package org.apache.pulsar.proxy.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.prometheus.client.Counter;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.BinaryProtoLookupService;
import org.apache.pulsar.common.api.proto.CommandGetSchema;
import org.apache.pulsar.common.api.proto.CommandGetTopicsOfNamespace;
import org.apache.pulsar.common.api.proto.CommandLookupTopic;
import org.apache.pulsar.common.api.proto.CommandLookupTopicResponse.LookupType;
import org.apache.pulsar.common.api.proto.CommandPartitionedTopicMetadata;
import org.apache.pulsar.common.api.proto.ServerError;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.protocol.schema.BytesSchemaVersion;
import org.apache.pulsar.common.protocol.schema.SchemaVersion;
import org.apache.pulsar.common.util.netty.NettyChannelUtil;
import org.apache.pulsar.policies.data.loadbalancer.ServiceLookupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LookupProxyHandler {
    private final String throttlingErrorMessage = "Too many concurrent lookup and partitionsMetadata requests";
    private final ProxyConnection proxyConnection;
    private final BrokerDiscoveryProvider discoveryProvider;
    private final boolean connectWithTLS;

    private final SocketAddress clientAddress;
    private final String brokerServiceURL;

    private static final Counter LOOKUP_REQUESTS = Counter
            .build("pulsar_proxy_lookup_requests", "Counter of topic lookup requests").create().register();

    private static final Counter PARTITIONS_METADATA_REQUESTS = Counter
            .build("pulsar_proxy_partitions_metadata_requests", "Counter of partitions metadata requests").create()
            .register();

    private static final Counter GET_TOPICS_OF_NAMESPACE_REQUESTS = Counter
            .build("pulsar_proxy_get_topics_of_namespace_requests", "Counter of getTopicsOfNamespace requests")
            .create()
            .register();

    private static final Counter GET_SCHEMA_REQUESTS = Counter
            .build("pulsar_proxy_get_schema_requests", "Counter of schema requests")
            .create()
            .register();

    static final Counter REJECTED_LOOKUP_REQUESTS = Counter.build("pulsar_proxy_rejected_lookup_requests",
            "Counter of topic lookup requests rejected due to throttling").create().register();

    static final Counter REJECTED_PARTITIONS_METADATA_REQUESTS = Counter
            .build("pulsar_proxy_rejected_partitions_metadata_requests",
                    "Counter of partitions metadata requests rejected due to throttling")
            .create().register();

    static final Counter REJECTED_GET_TOPICS_OF_NAMESPACE_REQUESTS = Counter
            .build("pulsar_proxy_rejected_get_topics_of_namespace_requests",
                    "Counter of getTopicsOfNamespace requests rejected due to throttling")
            .create().register();
    private final Semaphore lookupRequestSemaphore;

    public LookupProxyHandler(ProxyService proxy, ProxyConnection proxyConnection) {
        this.discoveryProvider = proxy.getDiscoveryProvider();
        this.lookupRequestSemaphore = proxy.getLookupRequestSemaphore();
        this.proxyConnection = proxyConnection;
        this.clientAddress = proxyConnection.clientAddress();
        this.connectWithTLS = proxy.getConfiguration().isTlsEnabledWithBroker();
        this.brokerServiceURL = this.connectWithTLS ? proxy.getConfiguration().getBrokerServiceURLTLS()
                : proxy.getConfiguration().getBrokerServiceURL();
    }

    public void handleLookup(CommandLookupTopic lookup) {
        if (log.isDebugEnabled()) {
            log.debug("Received Lookup from {}", clientAddress);
        }
        long clientRequestId = lookup.getRequestId();
        if (lookupRequestSemaphore.tryAcquire()) {
            try {
                LOOKUP_REQUESTS.inc();
                String serviceUrl = getBrokerServiceUrl(clientRequestId);
                if (serviceUrl != null) {
                    performLookup(clientRequestId, lookup.getTopic(), serviceUrl, false, 10);
                }
            } finally {
                lookupRequestSemaphore.release();
            }
        } else {
            REJECTED_LOOKUP_REQUESTS.inc();
            if (log.isDebugEnabled()) {
                log.debug("Lookup Request ID {} from {} rejected - {}.", clientRequestId, clientAddress,
                        throttlingErrorMessage);
            }
            writeAndFlush(Commands.newLookupErrorResponse(ServerError.TooManyRequests,
                    throttlingErrorMessage, clientRequestId));
        }

    }

    private void performLookup(long clientRequestId, String topic, String brokerServiceUrl, boolean authoritative,
            int numberOfRetries) {
        if (numberOfRetries == 0) {
            writeAndFlush(Commands.newLookupErrorResponse(ServerError.ServiceNotReady,
                    "Reached max number of redirections", clientRequestId));
            return;
        }

        URI brokerURI;
        try {
            brokerURI = new URI(brokerServiceUrl);
        } catch (URISyntaxException e) {
            writeAndFlush(
                    Commands.newLookupErrorResponse(ServerError.MetadataError, e.getMessage(), clientRequestId));
            return;
        }

        InetSocketAddress addr = InetSocketAddress.createUnresolved(brokerURI.getHost(), brokerURI.getPort());
        if (log.isDebugEnabled()) {
            log.debug("Getting connections to '{}' for Looking up topic '{}' with clientReq Id '{}'", addr, topic,
                    clientRequestId);
        }
        proxyConnection.getConnectionPool().getConnection(addr).thenAccept(clientCnx -> {
            // Connected to backend broker
            long requestId = proxyConnection.newRequestId();
            ByteBuf command;
            command = Commands.newLookup(topic, authoritative, requestId);

            clientCnx.newLookup(command, requestId).whenComplete((r, t) -> {
                if (t != null) {
                    log.warn("[{}] Failed to lookup topic {}: {}", clientAddress, topic, t.getMessage());
                    writeAndFlush(
                        Commands.newLookupErrorResponse(getServerError(t), t.getMessage(), clientRequestId));
                } else {
                    String brokerUrl = resolveBrokerUrlFromLookupDataResult(r);
                    if (r.redirect) {
                        // Need to try the lookup again on a different broker
                        performLookup(clientRequestId, topic, brokerUrl, r.authoritative, numberOfRetries - 1);
                    } else {
                        // Reply the same address for both TLS non-TLS. The reason
                        // is that whether we use TLS
                        // and broker is independent of whether the client itself
                        // uses TLS, but we need to force the
                        // client
                        // to use the appropriate target broker (and port) when it
                        // will connect back.
                        if (log.isDebugEnabled()) {
                            log.debug("Successfully perform lookup '{}' for topic '{}'"
                                            + " with clientReq Id '{}' and lookup-broker {}",
                                    addr, topic, clientRequestId, brokerUrl);
                        }
                        writeAndFlush(Commands.newLookupResponse(brokerUrl, brokerUrl, true,
                            LookupType.Connect, clientRequestId, true /* this is coming from proxy */));
                    }
                }
                proxyConnection.getConnectionPool().releaseConnection(clientCnx);
            });
        }).exceptionally(ex -> {
            // Failed to connect to backend broker
            writeAndFlush(
                    Commands.newLookupErrorResponse(getServerError(ex), ex.getMessage(), clientRequestId));
            return null;
        });
    }

    protected String resolveBrokerUrlFromLookupDataResult(BinaryProtoLookupService.LookupDataResult r) {
        return connectWithTLS ? r.brokerUrlTls : r.brokerUrl;
    }

    public void handlePartitionMetadataResponse(CommandPartitionedTopicMetadata partitionMetadata) {
        PARTITIONS_METADATA_REQUESTS.inc();
        if (log.isDebugEnabled()) {
            log.debug("[{}] Received PartitionMetadataLookup", clientAddress);
        }
        final long clientRequestId = partitionMetadata.getRequestId();
        if (lookupRequestSemaphore.tryAcquire()) {
            try {
                handlePartitionMetadataResponse(partitionMetadata, clientRequestId);
            } finally {
                lookupRequestSemaphore.release();
            }
        } else {
            REJECTED_PARTITIONS_METADATA_REQUESTS.inc();
            if (log.isDebugEnabled()) {
                log.debug("PartitionMetaData Request ID {} from {} rejected - {}.", clientRequestId, clientAddress,
                        throttlingErrorMessage);
            }
            writeAndFlush(Commands.newPartitionMetadataResponse(ServerError.ServiceNotReady,
                    throttlingErrorMessage, clientRequestId));
        }
    }

    /**
     *   Always get partition metadata from broker service.
     *
     *
     **/
    private void handlePartitionMetadataResponse(CommandPartitionedTopicMetadata partitionMetadata,
            long clientRequestId) {
        String topicName = TopicName.toFullTopicName(partitionMetadata.getTopic());

        String serviceUrl = getBrokerServiceUrl(clientRequestId);
        if (serviceUrl == null) {
            log.warn("No available broker for {} to lookup partition metadata", topicName);
            return;
        }
        InetSocketAddress addr = getAddr(serviceUrl, clientRequestId);
        if (addr == null) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Getting connections to '{}' for Looking up topic '{}' with clientReq Id '{}'", addr,
                    topicName, clientRequestId);
        }
        proxyConnection.getConnectionPool().getConnection(addr).thenAccept(clientCnx -> {
            // Connected to backend broker
            long requestId = proxyConnection.newRequestId();
            ByteBuf command;
            command = Commands.newPartitionMetadataRequest(topicName.toString(), requestId,
                    partitionMetadata.isMetadataAutoCreationEnabled());
            clientCnx.newLookup(command, requestId).whenComplete((r, t) -> {
                if (t != null) {
                    log.warn("[{}] failed to get Partitioned metadata : {}", topicName,
                        t.getMessage(), t);
                    PulsarClientException pce = PulsarClientException.unwrap(t);
                    writeAndFlush(Commands.newLookupErrorResponse(clientCnx.revertClientExToErrorCode(pce),
                            t.getMessage(), clientRequestId));
                } else {
                    writeAndFlush(
                        Commands.newPartitionMetadataResponse(r.partitions, clientRequestId));
                }
                proxyConnection.getConnectionPool().releaseConnection(clientCnx);
            });
        }).exceptionally(ex -> {
            // Failed to connect to backend broker
            writeAndFlush(Commands.newPartitionMetadataResponse(getServerError(ex),
                    ex.getMessage(), clientRequestId));
            return null;
        });
    }

    public void handleGetTopicsOfNamespace(CommandGetTopicsOfNamespace commandGetTopicsOfNamespace) {
        GET_TOPICS_OF_NAMESPACE_REQUESTS.inc();
        if (log.isDebugEnabled()) {
            log.debug("[{}] Received GetTopicsOfNamespace", clientAddress);
        }

        final long requestId = commandGetTopicsOfNamespace.getRequestId();

        if (lookupRequestSemaphore.tryAcquire()) {
            try {
                handleGetTopicsOfNamespace(commandGetTopicsOfNamespace, requestId);
            } finally {
                lookupRequestSemaphore.release();
            }
        } else {
            REJECTED_GET_TOPICS_OF_NAMESPACE_REQUESTS.inc();
            if (log.isDebugEnabled()) {
                log.debug("GetTopicsOfNamespace Request ID {} from {} rejected - {}.", requestId, clientAddress,
                    throttlingErrorMessage);
            }
            writeAndFlush(Commands.newError(
                requestId, ServerError.ServiceNotReady, throttlingErrorMessage
            ));
        }
    }

    private void handleGetTopicsOfNamespace(CommandGetTopicsOfNamespace commandGetTopicsOfNamespace,
                                            long clientRequestId) {
        String serviceUrl = getBrokerServiceUrl(clientRequestId);

        if (!StringUtils.isNotBlank(serviceUrl)) {
            return;
        }
        String topicsPattern = commandGetTopicsOfNamespace.hasTopicsPattern()
                ? commandGetTopicsOfNamespace.getTopicsPattern() : null;
        String topicsHash = commandGetTopicsOfNamespace.hasTopicsHash()
                ? commandGetTopicsOfNamespace.getTopicsHash() : null;
        performGetTopicsOfNamespace(clientRequestId, commandGetTopicsOfNamespace.getNamespace(), serviceUrl,
                10, topicsPattern, topicsHash, commandGetTopicsOfNamespace.getMode());
    }

    private void performGetTopicsOfNamespace(long clientRequestId,
                                             String namespaceName,
                                             String brokerServiceUrl,
                                             int numberOfRetries,
                                             String topicsPattern,
                                             String topicsHash,
                                             CommandGetTopicsOfNamespace.Mode mode) {
        if (numberOfRetries == 0) {
            writeAndFlush(Commands.newError(clientRequestId, ServerError.ServiceNotReady,
                    "Reached max number of redirections"));
            return;
        }

        InetSocketAddress addr = getAddr(brokerServiceUrl, clientRequestId);

        if (addr == null) {
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("Getting connections to '{}' for getting TopicsOfNamespace '{}' with clientReq Id '{}'",
                addr, namespaceName, clientRequestId);
        }
        proxyConnection.getConnectionPool().getConnection(addr).thenAccept(clientCnx -> {
            // Connected to backend broker
            long requestId = proxyConnection.newRequestId();
            ByteBuf command;
            command = Commands.newGetTopicsOfNamespaceRequest(namespaceName, requestId, mode,
                    topicsPattern, topicsHash);
            clientCnx.newGetTopicsOfNamespace(command, requestId).whenComplete((r, t) -> {
                if (t != null) {
                    log.warn("[{}] Failed to get TopicsOfNamespace {}: {}",
                            clientAddress, namespaceName, t.getMessage());
                    writeAndFlush(
                        Commands.newError(clientRequestId, getServerError(t), t.getMessage()));
                } else {
                    writeAndFlush(
                        Commands.newGetTopicsOfNamespaceResponse(r.getNonPartitionedOrPartitionTopics(),
                                r.getTopicsHash(), r.isFiltered(),
                                r.isChanged(), clientRequestId));
                }
            });

            proxyConnection.getConnectionPool().releaseConnection(clientCnx);
        }).exceptionally(ex -> {
            // Failed to connect to backend broker
            writeAndFlush(
                    Commands.newError(clientRequestId, getServerError(ex), ex.getMessage()));
            return null;
        });
    }

    public void handleGetSchema(CommandGetSchema commandGetSchema) {
        GET_SCHEMA_REQUESTS.inc();
        if (log.isDebugEnabled()) {
            log.debug("[{}] Received GetSchema {}", clientAddress, commandGetSchema);
        }

        final long clientRequestId = commandGetSchema.getRequestId();
        String serviceUrl = getBrokerServiceUrl(clientRequestId);
        String topic = commandGetSchema.getTopic();
        Optional<SchemaVersion> schemaVersion;
        if (commandGetSchema.hasSchemaVersion()) {
            schemaVersion = Optional.of(commandGetSchema.getSchemaVersion()).map(BytesSchemaVersion::of);
        } else {
            schemaVersion = Optional.empty();
        }

        if (!StringUtils.isNotBlank(serviceUrl)) {
            return;
        }
        InetSocketAddress addr = getAddr(serviceUrl, clientRequestId);

        if (addr == null) {
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("Getting connections to '{}' for getting schema of topic '{}' with clientReq Id '{}'",
                    addr, topic, clientRequestId);
        }

        proxyConnection.getConnectionPool().getConnection(addr).thenAccept(clientCnx -> {
            // Connected to backend broker
            long requestId = proxyConnection.newRequestId();
            ByteBuf command;
            command = Commands.newGetSchema(requestId, topic, schemaVersion);
            clientCnx.sendGetRawSchema(command, requestId).whenComplete((r, t) -> {
                if (t != null) {
                    log.warn("[{}] Failed to get schema {}: {}", clientAddress, topic, t);
                    writeAndFlush(
                        Commands.newError(clientRequestId, getServerError(t), t.getMessage()));
                } else {
                    writeAndFlush(
                        Commands.newGetSchemaResponse(clientRequestId, r));
                }

                proxyConnection.getConnectionPool().releaseConnection(clientCnx);
            });
        }).exceptionally(ex -> {
            // Failed to connect to backend broker
            writeAndFlush(
                    Commands.newError(clientRequestId, getServerError(ex), ex.getMessage()));
            return null;
        });

    }

    /**
     *  Get default broker service url or discovery an available broker.
     **/
    private String getBrokerServiceUrl(long clientRequestId) {
        if (StringUtils.isNotBlank(brokerServiceURL)) {
            return brokerServiceURL;
        }
        ServiceLookupData availableBroker;
        try {
            availableBroker = discoveryProvider.nextBroker();
        } catch (Exception e) {
            log.warn("[{}] Failed to get next active broker {}", clientAddress, e.getMessage(), e);
            writeAndFlush(Commands.newError(
                    clientRequestId, ServerError.ServiceNotReady, e.getMessage()
            ));
            return null;
        }
        return this.connectWithTLS ? availableBroker.getPulsarServiceUrlTls() : availableBroker.getPulsarServiceUrl();
    }

    private InetSocketAddress getAddr(String brokerServiceUrl, long clientRequestId) {
        URI brokerURI;
        try {
            brokerURI = new URI(brokerServiceUrl);
        } catch (URISyntaxException e) {
            writeAndFlush(
                    Commands.newError(clientRequestId, ServerError.MetadataError, e.getMessage()));
            return null;
        }
        return InetSocketAddress.createUnresolved(brokerURI.getHost(), brokerURI.getPort());
    }

    private ServerError getServerError(Throwable error) {
        ServerError responseError;
        if (error instanceof PulsarClientException.AuthorizationException) {
            responseError = ServerError.AuthorizationError;
        } else if (error instanceof PulsarClientException.AuthenticationException) {
            responseError = ServerError.AuthenticationError;
        } else {
            responseError = ServerError.ServiceNotReady;
        }
        return responseError;
    }

    private void writeAndFlush(ByteBuf cmd) {
        final ChannelHandlerContext ctx = proxyConnection.ctx();
        NettyChannelUtil.writeAndFlushWithVoidPromise(ctx, cmd);
    }

    private static final Logger log = LoggerFactory.getLogger(LookupProxyHandler.class);
}
