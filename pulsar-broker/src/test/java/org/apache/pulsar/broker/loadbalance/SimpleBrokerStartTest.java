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
package org.apache.pulsar.broker.loadbalance;

import com.google.common.io.Resources;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.impl.SimpleLoadManagerImpl;
import org.apache.pulsar.zookeeper.LocalBookkeeperEnsemble;
import org.testng.Assert;
import org.testng.annotations.Test;

@Slf4j
@Test(groups = "broker")
public class SimpleBrokerStartTest {

    private static final String caCertPath = Resources.getResource("certificate-authority/certs/ca.cert.pem")
            .getPath();
    private static final String brokerCertPath =
            Resources.getResource("certificate-authority/server-keys/broker.cert.pem").getPath();
    private static final String brokerKeyPath =
            Resources.getResource("certificate-authority/server-keys/broker.key-pk8.pem").getPath();

    public void testHasNICSpeed() throws Exception {
        if (!LinuxInfoUtils.isLinux()) {
            return;
        }
        // Start local bookkeeper ensemble
        @Cleanup("stop")
        LocalBookkeeperEnsemble bkEnsemble = new LocalBookkeeperEnsemble(3, 0, () -> 0);
        bkEnsemble.start();
        // Start broker
        ServiceConfiguration config = new ServiceConfiguration();
        config.setClusterName("use");
        config.setWebServicePort(Optional.of(0));
        config.setMetadataStoreUrl("zk:127.0.0.1:" + bkEnsemble.getZookeeperPort());
        config.setBrokerShutdownTimeoutMs(0L);
        config.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        config.setBrokerServicePort(Optional.of(0));
        config.setLoadManagerClassName(SimpleLoadManagerImpl.class.getName());
        config.setBrokerServicePortTls(Optional.of(0));
        config.setWebServicePortTls(Optional.of(0));
        config.setAdvertisedAddress("localhost");
        config.setTlsTrustCertsFilePath(caCertPath);
        config.setTlsCertificateFilePath(brokerCertPath);
        config.setTlsKeyFilePath(brokerKeyPath);
        boolean hasNicSpeeds = LinuxInfoUtils.checkHasNicSpeeds();
        if (hasNicSpeeds) {
            @Cleanup
            PulsarService pulsarService = new PulsarService(config);
            pulsarService.start();
        }
    }

    public void testNoNICSpeed() throws Exception {
        if (!LinuxInfoUtils.isLinux()) {
            return;
        }
        // Start local bookkeeper ensemble
        @Cleanup("stop")
        LocalBookkeeperEnsemble bkEnsemble = new LocalBookkeeperEnsemble(3, 0, () -> 0);
        bkEnsemble.start();
        // Start broker
        ServiceConfiguration config = new ServiceConfiguration();
        config.setClusterName("use");
        config.setWebServicePort(Optional.of(0));
        config.setMetadataStoreUrl("zk:127.0.0.1:" + bkEnsemble.getZookeeperPort());
        config.setBrokerShutdownTimeoutMs(0L);
        config.setLoadBalancerOverrideBrokerNicSpeedGbps(Optional.of(1.0d));
        config.setBrokerServicePort(Optional.of(0));
        config.setLoadManagerClassName(SimpleLoadManagerImpl.class.getName());
        config.setBrokerServicePortTls(Optional.of(0));
        config.setWebServicePortTls(Optional.of(0));
        config.setAdvertisedAddress("localhost");
        config.setTlsTrustCertsFilePath(caCertPath);
        config.setTlsCertificateFilePath(brokerCertPath);
        config.setTlsKeyFilePath(brokerKeyPath);
        boolean hasNicSpeeds = LinuxInfoUtils.checkHasNicSpeeds();
        if (!hasNicSpeeds) {
            @Cleanup
            PulsarService pulsarService = new PulsarService(config);
            pulsarService.start();
        }
    }


    @Test
    public void testCGroupMetrics() {
        if (!LinuxInfoUtils.isLinux()) {
            return;
        }

        boolean existsCGroup = Files.exists(Paths.get("/sys/fs/cgroup"));
        boolean cGroupEnabled = LinuxInfoUtils.isCGroupEnabled();
        Assert.assertEquals(cGroupEnabled, existsCGroup);

        double totalCpuLimit = LinuxInfoUtils.getTotalCpuLimit(cGroupEnabled);
        log.info("totalCpuLimit: {}", totalCpuLimit);
        Assert.assertTrue(totalCpuLimit > 0.0);

        if (cGroupEnabled) {
            Assert.assertNotNull(LinuxInfoUtils.getMetrics());

            long cpuUsageForCGroup = LinuxInfoUtils.getCpuUsageForCGroup();
            log.info("cpuUsageForCGroup: {}", cpuUsageForCGroup);
            Assert.assertTrue(cpuUsageForCGroup > 0);
        }
    }

}
