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
package org.apache.pulsar.policies.data.loadbalancer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.testng.Assert;
import org.testng.annotations.Test;

public class LocalBrokerDataTest {

    @Test
    public void testLocalBrokerDataDeserialization() throws JsonProcessingException {
        ObjectReader loadReportReader = ObjectMapperFactory.getMapper().reader()
                .forType(LoadManagerReport.class);
        String data =
                "{\"webServiceUrl\":\"http://10.244.2.23:8080\",\"webServiceUrlTls\":\"https://10.244.2.23:8081\","
                        + "\"pulsarServiceUrlTls\":\"pulsar+ssl://10.244.2.23:6651\",\"persistentTopicsEnabled\":true,"
                        + "\"nonPersistentTopicsEnabled\":false,\"cpu\":{\"usage\":3.1577712104798255,\"limit\":100.0},"
                        + "\"memory\":{\"usage\":614.0,\"limit\":1228.0},\"directMemory\":{\"usage\":32.0,"
                        + "\"limit\":1228.0},\"bandwidthIn\":{\"usage\":0.0,\"limit\":0.0},"
                        + "\"bandwidthOut\":{\"usage\":0.0,\"limit\":0.0},\"msgThroughputIn\":0.0,"
                        + "\"msgThroughputOut\":0.0,\"msgRateIn\":0.0,\"msgRateOut\":0.0,\"lastUpdate\":1650886425227,"
                        + "\"lastStats\":{\"pulsar/pulsar/10.244.2.23:8080/0x00000000_0xffffffff\":{\"msgRateIn\":0.0,"
                        + "\"msgThroughputIn\":0.0,\"msgRateOut\":0.0,\"msgThroughputOut\":0.0,\"consumerCount\":0,"
                        + "\"producerCount\":0,\"topics\":1,\"cacheSize\":0}},\"numTopics\":1,\"numBundles\":1,"
                        + "\"numConsumers\":0,\"numProducers\":0,"
                        + "\"bundles\":[\"pulsar/pulsar/10.244.2.23:8080/0x00000000_0xffffffff\"],"
                        + "\"lastBundleGains\":[],\"lastBundleLosses\":[],"
                        + "\"brokerVersionString\":\"2.11.0-hw-0.0.4-SNAPSHOT\",\"protocols\":{},"
                        + "\"advertisedListeners\":{},"
                        + "\"bundleStats\":{\"pulsar/pulsar/10.244.2.23:8080/0x00000000_0xffffffff\":{\"msgRateIn\":0."
                        + "0,\"msgThroughputIn\":0.0,\"msgRateOut\":0.0,\"msgThroughputOut\":0.0,\"consumerCount\":0,"
                        + "\"producerCount\":0,\"topics\":1,\"cacheSize\":0}},\"maxResourceUsage\":0.49645519256591797,"
                        + "\"loadReportType\":\"LocalBrokerData\"}";
        LoadManagerReport localBrokerData = loadReportReader.readValue(data);
        Assert.assertEquals(localBrokerData.getMemory().limit, 1228.0d, 0.0001f);
        Assert.assertEquals(localBrokerData.getMemory().usage, 614.0d, 0.0001f);
        Assert.assertEquals(localBrokerData.getMemory().percentUsage(),
              ((float) localBrokerData.getMemory().usage) / ((float) localBrokerData.getMemory().limit) * 100, 0.0001f);
    }
    @Test
    public void testTimeAverageBrokerDataDataDeserialization() throws JsonProcessingException {
        ObjectReader timeAverageReader = ObjectMapperFactory.getMapper().reader()
                .forType(TimeAverageBrokerData.class);
        String data = "{\"shortTermMsgThroughputIn\":100,\"shortTermMsgThroughputOut\":200,\"shortTermMsgRateIn\":300,"
                + "\"shortTermMsgRateOut\":400,\"longTermMsgThroughputIn\":567.891,\"longTermMsgThroughputOut\""
                + ":678.912,\"longTermMsgRateIn\":789.123,\"longTermMsgRateOut\":890.123}";
        TimeAverageBrokerData timeAverageBrokerData = timeAverageReader.readValue(data);
        assertEquals(timeAverageBrokerData.getShortTermMsgThroughputIn(), 100.00);
        assertEquals(timeAverageBrokerData.getShortTermMsgThroughputOut(), 200.00);
        assertEquals(timeAverageBrokerData.getShortTermMsgRateIn(), 300.00);
        assertEquals(timeAverageBrokerData.getShortTermMsgRateOut(), 400.00);
    }

    @Test
    public void testMaxResourceUsage() {
        LocalBrokerData data = new LocalBrokerData();
        data.setCpu(new ResourceUsage(1.0, 100.0));
        data.setDirectMemory(new ResourceUsage(2.0, 100.0));
        data.setBandwidthIn(new ResourceUsage(3.0, 100.0));
        data.setBandwidthOut(new ResourceUsage(4.0, 100.0));

        double epsilon = 0.00001;
        double weight = 0.5;
        // skips memory usage
        assertEquals(data.getMaxResourceUsage(), data.getBandwidthOut().percentUsage() / 100, epsilon);

        assertEquals(data.getMaxResourceUsageWithWeight(weight, weight, weight, weight),
                data.getBandwidthOut().percentUsage() * weight / 100, epsilon);
    }

    /*
    Ensure that there is no bundleStats field in the json string serialized from LocalBrokerData.
     */
    @Test
    public void testSerializeLocalBrokerData() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        LocalBrokerData localBrokerData = new LocalBrokerData();
        assertFalse(objectMapper.writeValueAsString(localBrokerData).contains("bundleStats"));
    }
}
