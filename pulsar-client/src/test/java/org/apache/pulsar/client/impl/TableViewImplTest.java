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
package org.apache.pulsar.client.impl;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotNull;
import org.apache.pulsar.client.api.CryptoKeyReader;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TableView;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TableViewImplTest {

    private PulsarClientImpl client;
    private TableViewConfigurationData data;

    @BeforeClass(alwaysRun = true)
    public void setup() {
        client = mock(PulsarClientImpl.class);
        ConnectionPool connectionPool = mock(ConnectionPool.class);
        when(client.getCnxPool()).thenReturn(connectionPool);
        when(client.newReader(any(Schema.class)))
            .thenReturn(new ReaderBuilderImpl(client, Schema.BYTES));

        data = new TableViewConfigurationData();
        data.setTopicName("testTopicName");
    }

    @Test
    public void testTableViewImpl() {
        data.setCryptoKeyReader(mock(CryptoKeyReader.class));
        TableView tableView = new TableViewImpl(client, Schema.BYTES, data);

        assertNotNull(tableView);
    }
}
