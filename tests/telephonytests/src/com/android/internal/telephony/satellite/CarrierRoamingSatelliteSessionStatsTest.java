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

package com.android.internal.telephony.satellite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.telephony.ServiceState;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.flags.FeatureFlags;
import com.android.internal.telephony.metrics.SatelliteStats;
import com.android.internal.telephony.satellite.metrics.CarrierRoamingSatelliteSessionStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CarrierRoamingSatelliteSessionStatsTest extends TelephonyTest {
    private static final String TAG = "CarrierRoamingSatelliteSessionStatsTest";
    private static final int SUB_ID = 0;
    private static final int CARRIER_ID = 1234;

    @Mock private SatelliteStats mMockSatelliteStats;
    @Mock private Phone mMockPhone;
    @Mock private ServiceState mMockServiceState;
    @Mock private FeatureFlags mMockFeatureFlags;
    @Mock private BatteryManager mMockBatteryManager;

    private TestCarrierRoamingSatelliteSessionStats mCarrierRoamingSatelliteSessionStats;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);
        logd(TAG + " Setup!");

        mContextFixture.setSystemService(Context.BATTERY_SERVICE, mMockBatteryManager);
        replaceInstance(SatelliteStats.class, "sInstance", null, mMockSatelliteStats);

        doReturn(mContext).when(mMockPhone).getContext();
        doReturn(SUB_ID).when(mMockPhone).getSubId();
        doReturn(CARRIER_ID).when(mMockPhone).getCarrierId();
        doReturn(mMockServiceState).when(mMockPhone).getServiceState();

        mCarrierRoamingSatelliteSessionStats = new TestCarrierRoamingSatelliteSessionStats(SUB_ID);
    }

    @After
    public void tearDown() throws Exception {
        CarrierRoamingSatelliteSessionStats.clearInstancesForTest();
        super.tearDown();
    }

    @Test
    public void testAccumulatedScreenOnTime() {
        doReturn(true).when(mMockFeatureFlags).satelliteMetricsEnhancement();
        doReturn(new int[]{SUB_ID}).when(mSubscriptionManagerService).getActiveSubIdList(
                anyBoolean());
        mCarrierRoamingSatelliteSessionStats.increaseElapsedTime(1_000L);
        mCarrierRoamingSatelliteSessionStats.onSessionStart(
                CARRIER_ID,
                mMockPhone,
                new int[] {},
                0,
                Collections.emptyList(),
                0,
                0,
                "12345",
                mMockFeatureFlags,
                true /* isScreenOn */,
                true /* isWiFiConnected */);

        // Advance time by 5 seconds
        mCarrierRoamingSatelliteSessionStats.increaseElapsedTime(5_000L);
        // Screen turns off
        mCarrierRoamingSatelliteSessionStats.onScreenStateChanged(false);

        // Advance time by 10 seconds (screen off)
        mCarrierRoamingSatelliteSessionStats.increaseElapsedTime(10_000L);

        // Screen turns on
        mCarrierRoamingSatelliteSessionStats.onScreenStateChanged(true);
        // Advance time by 8 seconds
        mCarrierRoamingSatelliteSessionStats.increaseElapsedTime(8_000L);

        // End session
        mCarrierRoamingSatelliteSessionStats.onSessionEnd(SUB_ID, Collections.emptyList());

        ArgumentCaptor<SatelliteStats.CarrierRoamingSatelliteSessionParams> captor =
                ArgumentCaptor.forClass(SatelliteStats.CarrierRoamingSatelliteSessionParams.class);
        verify(mMockSatelliteStats).onCarrierRoamingSatelliteSessionMetrics(captor.capture());

        SatelliteStats.CarrierRoamingSatelliteSessionParams params = captor.getValue();
        // 5s + 8s = 13s
        assertEquals(13, params.getScreenOnTimeSec());
    }

    @Test
    public void testWifiConnectivityState() {
        doReturn(true).when(mMockFeatureFlags).satelliteMetricsEnhancement();
        doReturn(new int[]{SUB_ID}).when(mSubscriptionManagerService).getActiveSubIdList(
                anyBoolean());

        // Test scenario 1:  WiFi was disconnected when session start.
        mCarrierRoamingSatelliteSessionStats.onSessionStart(CARRIER_ID, mMockPhone, new int[]{}, 0,
                Collections.emptyList(), 0, 0, "12345", mMockFeatureFlags, true /* isScreenOn */,
                false /* isWiFiConnected */);

        // WiFi was connected.
        boolean expectedWifiConnectedStatus = true;
        mCarrierRoamingSatelliteSessionStats.onWifiConnectivityStateChanged(
                expectedWifiConnectedStatus);
        mCarrierRoamingSatelliteSessionStats.onSessionEnd(SUB_ID, Collections.emptyList());

        ArgumentCaptor<SatelliteStats.CarrierRoamingSatelliteSessionParams> captor =
                ArgumentCaptor.forClass(SatelliteStats.CarrierRoamingSatelliteSessionParams.class);
        verify(mMockSatelliteStats).onCarrierRoamingSatelliteSessionMetrics(captor.capture());

        SatelliteStats.CarrierRoamingSatelliteSessionParams params = captor.getValue();
        // WiFi connected stats should betrue
        assertEquals(expectedWifiConnectedStatus, params.isWifiConnected());

        // Test scenario 2: WiFi was disconnected when session start.
        clearInvocations(mMockSatelliteStats);
        mCarrierRoamingSatelliteSessionStats.onSessionStart(CARRIER_ID, mMockPhone, new int[]{}, 0,
                Collections.emptyList(), 0, 0, "12345", mMockFeatureFlags, true /* isScreenOn */,
                false /* isWiFiConnected */);

        // WiFi was connected.
        expectedWifiConnectedStatus = true;
        mCarrierRoamingSatelliteSessionStats.onWifiConnectivityStateChanged(
                expectedWifiConnectedStatus);

        // Then disconnected
        mCarrierRoamingSatelliteSessionStats.onWifiConnectivityStateChanged(false);
        mCarrierRoamingSatelliteSessionStats.onSessionEnd(SUB_ID, Collections.emptyList());

        captor = ArgumentCaptor.forClass(SatelliteStats.CarrierRoamingSatelliteSessionParams.class);
        verify(mMockSatelliteStats).onCarrierRoamingSatelliteSessionMetrics(captor.capture());
        params = captor.getValue();

        // WiFi connected stats should be true
        assertEquals(expectedWifiConnectedStatus, params.isWifiConnected());

        // Test scenario 3: WiFi was connected when session start.
        clearInvocations(mMockSatelliteStats);
        mCarrierRoamingSatelliteSessionStats.onSessionStart(CARRIER_ID, mMockPhone, new int[]{}, 0,
                Collections.emptyList(), 0, 0, "12345", mMockFeatureFlags, true /* isScreenOn */,
                false /* isWiFiConnected */);

        // WiFi was disconnected.
        expectedWifiConnectedStatus = false;
        mCarrierRoamingSatelliteSessionStats.onWifiConnectivityStateChanged(
                expectedWifiConnectedStatus);
        mCarrierRoamingSatelliteSessionStats.onSessionEnd(SUB_ID, Collections.emptyList());

        captor = ArgumentCaptor.forClass(SatelliteStats.CarrierRoamingSatelliteSessionParams.class);
        verify(mMockSatelliteStats).onCarrierRoamingSatelliteSessionMetrics(captor.capture());
        params = captor.getValue();

        // WiFi connected stats should be false
        assertEquals(expectedWifiConnectedStatus, params.isWifiConnected());
    }

    @Test
    public void testBatteryMetrics() {
        doReturn(true).when(mMockFeatureFlags).satelliteMetricsEnhancement();
        doReturn(new int[]{SUB_ID}).when(mSubscriptionManagerService).getActiveSubIdList(
                anyBoolean());

        // Start state
        int startBatteryLevel = 80;
        long startEnergyCounter = 12345L;
        doReturn(startBatteryLevel).when(mMockBatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        doReturn(startEnergyCounter).when(mMockBatteryManager)
                .getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);
        doReturn(false).when(mMockBatteryManager).isCharging();

        mCarrierRoamingSatelliteSessionStats.onSessionStart(
                CARRIER_ID,
                mMockPhone,
                new int[]{},
                0,
                Collections.emptyList(),
                0,
                0,
                "12345",
                mMockFeatureFlags,
                true /* isScreenOn */,
                false);

        // Simulate charging event during session
        mCarrierRoamingSatelliteSessionStats.setWasChargingDuringSession();

        // End state
        int endBatteryLevel = 70;
        long endEnergyCounter = 10000L;
        doReturn(endBatteryLevel).when(mMockBatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        doReturn(endEnergyCounter).when(mMockBatteryManager)
                .getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER);

        mCarrierRoamingSatelliteSessionStats.onSessionEnd(SUB_ID, Collections.emptyList());

        ArgumentCaptor<SatelliteStats.CarrierRoamingSatelliteSessionParams> captor =
                ArgumentCaptor.forClass(SatelliteStats.CarrierRoamingSatelliteSessionParams.class);
        verify(mMockSatelliteStats).onCarrierRoamingSatelliteSessionMetrics(captor.capture());

        SatelliteStats.CarrierRoamingSatelliteSessionParams params = captor.getValue();

        assertEquals(startBatteryLevel - endBatteryLevel, params.getBatteryLevelDropPercent());
        assertEquals(startEnergyCounter - endEnergyCounter, params.getEnergyConsumedNwh());
        assertTrue(params.wasChargingDuringSession());
    }

    @Test
    public void testBatteryMetrics_EnhancementDisabled() {
        doReturn(false).when(mMockFeatureFlags).satelliteMetricsEnhancement();
        doReturn(new int[]{SUB_ID}).when(mSubscriptionManagerService).getActiveSubIdList(
                anyBoolean());

        mCarrierRoamingSatelliteSessionStats.onSessionStart(
                CARRIER_ID,
                mMockPhone,
                new int[] {},
                0,
                Collections.emptyList(),
                0,
                0,
                "12345",
                mMockFeatureFlags,
                true /* isScreenOn */,
                false);

        mCarrierRoamingSatelliteSessionStats.onSessionEnd(SUB_ID, Collections.emptyList());

        ArgumentCaptor<SatelliteStats.CarrierRoamingSatelliteSessionParams> captor =
                ArgumentCaptor.forClass(SatelliteStats.CarrierRoamingSatelliteSessionParams.class);
        verify(mMockSatelliteStats).onCarrierRoamingSatelliteSessionMetrics(captor.capture());

        SatelliteStats.CarrierRoamingSatelliteSessionParams params = captor.getValue();

        assertEquals(-1, params.getBatteryLevelDropPercent());
        assertEquals(-1, params.getEnergyConsumedNwh());
    }

    @Test
    public void testRxTxDataUsageMapping() {
        doReturn(true).when(mMockFeatureFlags).satelliteDataMetricsEnhancement();
        doReturn(new int[]{SUB_ID}).when(mSubscriptionManagerService).getActiveSubIdList(
                anyBoolean());

        // Baseline: Session start usage
        mCarrierRoamingSatelliteSessionStats.setMockDataUsage(1000L, 500L); // Rx=1000, Tx=500

        List<String> satelliteApps = List.of("app.A", "app.B");
        mCarrierRoamingSatelliteSessionStats.setMockPerAppDataUsage("app.A", 200L, 100L);
        mCarrierRoamingSatelliteSessionStats.setMockPerAppDataUsage("app.B", 50L, 50L);

        mCarrierRoamingSatelliteSessionStats.onSessionStart(
                CARRIER_ID, mMockPhone, new int[] {}, 0, satelliteApps, 0, 0, "12345",
                mMockFeatureFlags, true, true);

        // Simulation: Data usage increases during session
        // Session Total: Rx +500 (1500 total), Tx +300 (800 total)
        mCarrierRoamingSatelliteSessionStats.setMockDataUsage(1500L, 800L);

        // App A: Rx +100, Tx +50 (Total Delta = 150)
        mCarrierRoamingSatelliteSessionStats.setMockPerAppDataUsage("app.A", 300L, 150L);
        // App B: Rx +10, Tx +40 (Total Delta = 50)
        mCarrierRoamingSatelliteSessionStats.setMockPerAppDataUsage("app.B", 60L, 90L);

        // End session
        mCarrierRoamingSatelliteSessionStats.onSessionEnd(SUB_ID, satelliteApps);

        ArgumentCaptor<SatelliteStats.CarrierRoamingSatelliteSessionParams> captor =
                ArgumentCaptor.forClass(SatelliteStats.CarrierRoamingSatelliteSessionParams.class);
        verify(mMockSatelliteStats).onCarrierRoamingSatelliteSessionMetrics(captor.capture());

        SatelliteStats.CarrierRoamingSatelliteSessionParams params = captor.getValue();

        // 1. Verify Totals
        assertEquals(500L, params.getTotalRxDataBytes());
        assertEquals(300L, params.getTotalTxDataBytes());

        // 2. Verify Per-App Mapping (Top 5)
        // Index 0 should be app.A (Total 150 > Total 50)
        assertEquals("app.A", params.getSatelliteSupportedApps()[0]);
        assertEquals(100L, params.getPerAppRxDataBytes()[0]);
        assertEquals(50L, params.getPerAppTxDataBytes()[0]);

        // Index 1 should be app.B
        assertEquals("app.B", params.getSatelliteSupportedApps()[1]);
        assertEquals(10L, params.getPerAppRxDataBytes()[1]);
        assertEquals(40L, params.getPerAppTxDataBytes()[1]);
    }

    private static class TestCarrierRoamingSatelliteSessionStats
            extends CarrierRoamingSatelliteSessionStats {
        private long mElapsedTime = 0;
        private long mRxUsage = 0;
        private long mTxUsage = 0;
        private Map<String, DataUsage> mPerAppUsage = new HashMap<>();

        TestCarrierRoamingSatelliteSessionStats(int subId) {
            super(subId);
        }

        @Override
        protected long getElapsedRealtime() {
            return mElapsedTime;
        }

        public void increaseElapsedTime(long time) {
            mElapsedTime += time;
        }

        void setMockDataUsage(long rx, long tx) {
            mRxUsage = rx;
            mTxUsage = tx;
        }

        void setMockPerAppDataUsage(String pkg, long rx, long tx) {
            mPerAppUsage.put(pkg, new DataUsage(rx, tx));
        }

        @Override
        protected DataUsage getDetailedDataUsage() {
            return new DataUsage(mRxUsage, mTxUsage);
        }

        @Override
        protected long getDataUsage() {
            return mRxUsage + mTxUsage;
        }

        @Override
        protected Map<String, DataUsage> getPerAppDetailedDataUsage(List<String> apps) {
            return new HashMap<>(mPerAppUsage);
        }

        @Override
        protected Map<String, Long> getPerAppSatelliteDataUsage(List<String> apps) {
            Map<String, Long> totalMap = new HashMap<>();
            for (Map.Entry<String, DataUsage> entry : mPerAppUsage.entrySet()) {
                totalMap.put(entry.getKey(), entry.getValue().getTotalBytes());
            }
            return totalMap;
        }
    }
}
