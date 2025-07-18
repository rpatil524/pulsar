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
package org.apache.pulsar.broker.service.nonpersistent;

import io.netty.buffer.ByteBuf;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.Position;
import org.apache.pulsar.broker.PulsarServerException;
import org.apache.pulsar.broker.service.AbstractReplicator;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.Replicator;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.MessageImpl;
import org.apache.pulsar.client.impl.OpSendMsgStats;
import org.apache.pulsar.client.impl.ProducerImpl;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.client.impl.SendCallback;
import org.apache.pulsar.common.policies.data.stats.NonPersistentReplicatorStatsImpl;
import org.apache.pulsar.common.stats.Rate;
import org.apache.pulsar.common.util.FutureUtil;

@Slf4j
public class NonPersistentReplicator extends AbstractReplicator implements Replicator {

    private final Rate msgOut = new Rate();
    private final Rate msgDrop = new Rate();

    private final NonPersistentReplicatorStatsImpl stats = new NonPersistentReplicatorStatsImpl();

    public NonPersistentReplicator(NonPersistentTopic topic, String localCluster, String remoteCluster,
            BrokerService brokerService, PulsarClientImpl replicationClient) throws PulsarServerException {
        super(localCluster, topic, remoteCluster, topic.getName(), topic.getReplicatorPrefix(), brokerService,
                replicationClient);
        // NonPersistentReplicator does not support limitation so far, so reset pending queue size to the default value.
        producerBuilder.maxPendingMessages(1000);
        producerBuilder.blockIfQueueFull(false);
        startProducer();
    }

    /**
     * @return Producer name format : replicatorPrefix.localCluster-->remoteCluster
     */
    @Override
    protected String getProducerName() {
        return getReplicatorName(replicatorPrefix, localCluster) + REPL_PRODUCER_NAME_DELIMITER + remoteCluster;
    }

    @Override
    protected void setProducerAndTriggerReadEntries(Producer<byte[]> producer) {
        this.producer = (ProducerImpl) producer;

        if (STATE_UPDATER.compareAndSet(this, State.Starting, State.Started)) {
            log.info("[{}] Created replicator producer", replicatorId);
            backOff.reset();
        } else {
            log.info(
                    "[{}] Replicator was stopped while creating the producer."
                            + " Closing it. Replicator state: {}",
                    replicatorId, STATE_UPDATER.get(this));
            doCloseProducerAsync(producer, () -> {});
            return;
        }
    }

    public void sendMessage(Entry entry) {
        if ((STATE_UPDATER.get(this) == State.Started) && isWritable()) {

            int length = entry.getLength();
            ByteBuf headersAndPayload = entry.getDataBuffer();
            MessageImpl msg;
            try {
                msg = MessageImpl.deserializeSkipBrokerEntryMetaData(headersAndPayload);
            } catch (Throwable t) {
                log.error("[{}] Failed to deserialize message at {} (buffer size: {}): {}", replicatorId,
                        entry.getPosition(), length, t.getMessage(), t);
                entry.release();
                return;
            }

            if (msg.isReplicated()) {
                // Discard messages that were already replicated into this region
                entry.release();
                msg.recycle();
                return;
            }

            if (msg.hasReplicateTo() && !msg.getReplicateTo().contains(remoteCluster)) {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Skipping message at {} / msg-id: {}: replicateTo {}", replicatorId,
                            entry.getPosition(), msg.getMessageId(), msg.getReplicateTo());
                }
                entry.release();
                msg.recycle();
                return;
            }

            msgOut.recordEvent(headersAndPayload.readableBytes());
            stats.incrementMsgOutCounter();
            stats.incrementBytesOutCounter(headersAndPayload.readableBytes());

            msg.setReplicatedFrom(localCluster);

            headersAndPayload.retain();

            producer.sendAsync(msg, ProducerSendCallback.create(this, entry, msg));

        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] dropping message because replicator producer is not started/writable",
                        replicatorId);
            }
            msgDrop.recordEvent();
            stats.incrementMsgDropCount();
            entry.release();
        }
    }

    @Override
    public void updateRates() {
        msgOut.calculateRate();
        msgDrop.calculateRate();
        stats.msgRateOut = msgOut.getRate();
        stats.msgThroughputOut = msgOut.getValueRate();
        stats.msgDropRate = msgDrop.getRate();
    }

    @Override
    public NonPersistentReplicatorStatsImpl computeStats() {
        ProducerImpl producer = this.producer;
        stats.connected = isConnected();
        stats.replicationDelayInSeconds = TimeUnit.MILLISECONDS.toSeconds(getReplicationDelayMs());

        if (producer != null) {
            stats.outboundConnection = producer.getConnectionId();
            stats.outboundConnectedSince = producer.getConnectedSince();
        } else {
            stats.outboundConnection = null;
            stats.outboundConnectedSince = null;
        }

        return stats;
    }

    @Override
    public NonPersistentReplicatorStatsImpl getStats() {
        return stats;
    }

    private static final class ProducerSendCallback implements SendCallback {
        private NonPersistentReplicator replicator;
        private Entry entry;
        private MessageImpl msg;

        @Override
        public void sendComplete(Throwable exception, OpSendMsgStats opSendMsgStats) {
            if (exception != null) {
                Throwable actEx = FutureUtil.unwrapCompletionException(exception);
                if (actEx instanceof PulsarClientException.ProducerQueueIsFullError) {
                    log.warn("[{}] Discard to replicate non-persistent messages to the remote cluster because the"
                        + " producer pending queue is full", replicator.replicatorId);
                } else {
                    log.error("[{}] Error producing on remote broker", replicator.replicatorId, exception);
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Message persisted on remote broker", replicator.replicatorId);
                }
            }
            entry.release();

            recycle();
        }

        private final Handle<ProducerSendCallback> recyclerHandle;

        private ProducerSendCallback(Handle<ProducerSendCallback> recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        static ProducerSendCallback create(NonPersistentReplicator replicator, Entry entry, MessageImpl msg) {
            ProducerSendCallback sendCallback = RECYCLER.get();
            sendCallback.replicator = replicator;
            sendCallback.entry = entry;
            sendCallback.msg = msg;
            return sendCallback;
        }

        private void recycle() {
            replicator = null;
            entry = null; // already released and recycled on sendComplete
            if (msg != null) {
                msg.recycle();
                msg = null;
            }
            recyclerHandle.recycle(this);
        }

        private static final Recycler<ProducerSendCallback> RECYCLER = new Recycler<ProducerSendCallback>() {
            @Override
            protected ProducerSendCallback newObject(Handle<ProducerSendCallback> handle) {
                return new ProducerSendCallback(handle);
            }

        };

        @Override
        public void addCallback(MessageImpl<?> msg, SendCallback scb) {
            // noop
        }

        @Override
        public SendCallback getNextSendCallback() {
            return null;
        }

        @Override
        public MessageImpl<?> getNextMessage() {
            return null;
        }

        @Override
        public CompletableFuture<MessageId> getFuture() {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    protected Position getReplicatorReadPosition() {
        // No-op
        return null;
    }

    @Override
    public long getNumberOfEntriesInBacklog() {
        // No-op
        return 0;
    }

    @Override
    protected void disableReplicatorRead() {
        // No-op
    }

    @Override
    protected void beforeTerminate() {
        // No-op
    }
}
