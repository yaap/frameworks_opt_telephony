/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;

import com.android.internal.telephony.TelephonyConfigData;
import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
public class DataConfigParserTest extends TelephonyTest {

    private byte[] mBytesProtoBuffer;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");

        TelephonyConfigData.TelephonyConfigProto.Builder telephonyConfigBuilder =
                TelephonyConfigData.TelephonyConfigProto.newBuilder();
        TelephonyConfigData.DataConfigProto.Builder dataConfigBuilder =
                TelephonyConfigData.DataConfigProto.newBuilder();

        // version
        dataConfigBuilder.setVersion(1);

        TelephonyConfigData.ConnectionCapabilityConfig.Builder connectionCapabilityConfigBuilder =
                TelephonyConfigData.ConnectionCapabilityConfig.newBuilder();

        // Default Connection Capability
        TelephonyConfigData.ConnectionCapabilityMap.Builder defaultMapBuilder =
                TelephonyConfigData.ConnectionCapabilityMap.newBuilder();
        defaultMapBuilder.addRules("123:456:true");
        connectionCapabilityConfigBuilder.setDefaultConnectionCapabilityConfig(defaultMapBuilder);

        // Carrier Connection Capability
        TelephonyConfigData.ConnectionCapabilityMap.Builder carrierMapBuilder =
                TelephonyConfigData.ConnectionCapabilityMap.newBuilder();
        carrierMapBuilder.setCarrierId(1);
        carrierMapBuilder.addRules("111:222:false");
        connectionCapabilityConfigBuilder.addCarrierConnectionCapabilityConfigs(carrierMapBuilder);

        dataConfigBuilder.setConnectionCapabilityConfigs(connectionCapabilityConfigBuilder);

        telephonyConfigBuilder.setData(dataConfigBuilder);

        TelephonyConfigData.TelephonyConfigProto telephonyConfigData =
                telephonyConfigBuilder.build();
        mBytesProtoBuffer = telephonyConfigData.toByteArray();
    }

    @After
    public void tearDown() throws Exception {
        logd(TAG + " tearDown");
        super.tearDown();
    }

    @Test
    public void testParseData() {
        DataConfigParser parser = new DataConfigParser(mBytesProtoBuffer);
        assertNotNull(parser.getConfig());
        assertEquals(1, parser.getVersion());

        DataConfig config = parser.getConfig();
        assertNotNull(config.getConfigData());
        assertTrue(config.getConfigData().hasConnectionCapabilityConfigs());

        TelephonyConfigData.ConnectionCapabilityConfig caps =
                config.getConfigData().getConnectionCapabilityConfigs();

        assertTrue(caps.hasDefaultConnectionCapabilityConfig());
        assertEquals(1, caps.getDefaultConnectionCapabilityConfig().getRulesCount());
        assertEquals("123:456:true", caps.getDefaultConnectionCapabilityConfig().getRules(0));

        assertEquals(1, caps.getCarrierConnectionCapabilityConfigsCount());
        assertEquals(1, caps.getCarrierConnectionCapabilityConfigs(0).getCarrierId());
        assertEquals("111:222:false", caps.getCarrierConnectionCapabilityConfigs(0).getRules(0));
    }

    @Test
    public void testParseNullData() {
        DataConfigParser parser = new DataConfigParser((byte[]) null);
        assertNull(parser.getConfig());
    }

    @Test
    public void testGetDomain() {
        DataConfigParser parser = new DataConfigParser(mBytesProtoBuffer);
        assertEquals("data", parser.getDomain());
    }
}
