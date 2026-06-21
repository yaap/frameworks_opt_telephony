/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.net.NetworkCapabilities;
import android.os.Looper;
import android.os.PersistableBundle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telephony.CarrierConfigManager;
import android.telephony.SignalStrength;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.telephony.data.TrafficDescriptor;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.internal.telephony.TelephonyConfigData;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.configupdate.ConfigParser;
import com.android.internal.telephony.configupdate.ConfigProviderAdaptor;
import com.android.internal.telephony.configupdate.TelephonyConfigUpdateInstallReceiver;
import com.android.internal.telephony.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Set;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DataConfigManagerTest extends TelephonyTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private DataConfigManager mDataConfigManagerUT;
    private PersistableBundle mBundle;

    // Add these mocks
    private TelephonyConfigUpdateInstallReceiver mMockConfigReceiver;
    private ConfigParser mMockConfigParser;

    @Before
    public void setUp() throws Exception {
        logd("DataConfigManagerTest +Setup!");
        super.setUp(getClass().getSimpleName());

        doReturn(1).when(mPhone).getCarrierId();

        // Mock the Config Receiver singleton
        mMockConfigReceiver = mock(TelephonyConfigUpdateInstallReceiver.class);
        replaceInstance(TelephonyConfigUpdateInstallReceiver.class, "sReceiverAdaptorInstance",
                null, mMockConfigReceiver);

        // Mock the Parser returned by the receiver
        mMockConfigParser = mock(DataConfigParser.class);
        doReturn(mMockConfigParser).when(mMockConfigReceiver).getConfigParser(any());

        mBundle = mContextFixture.getCarrierConfigBundle();
        mDataConfigManagerUT = new DataConfigManager(mPhone, Looper.myLooper(), mFeatureFlags);
        logd("DataConfigManagerTest -Setup!");
    }

    @After
    public void tearDown() throws Exception {
        logd("tearDown");
        mDataConfigManagerUT = null;
        super.tearDown();
    }

    @Test
    public void testParseSlidingWindowCounterThreshold() {
        long defaultTimeWindow = 0;
        int defaultOccurrence = 2;
        DataConfigManager.EventFrequency defaultValue = new DataConfigManager.EventFrequency(0, 2);

        DataConfigManager.EventFrequency normal =
                mDataConfigManagerUT.parseSlidingWindowCounterThreshold(Long.MAX_VALUE + ","
                        + Integer.MAX_VALUE, defaultTimeWindow, defaultOccurrence);
        DataConfigManager.EventFrequency expected =
                new DataConfigManager.EventFrequency(Long.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(normal.timeWindow).isEqualTo(expected.timeWindow);
        assertThat(normal.eventNumOccurrence).isEqualTo(expected.eventNumOccurrence);

        //allow " time , occurrences ," as we can infer even though format is not strictly valid
        DataConfigManager.EventFrequency invalidFormat = mDataConfigManagerUT
                .parseSlidingWindowCounterThreshold(
                        Long.MAX_VALUE + "," + Integer.MAX_VALUE + " ,",
                        defaultTimeWindow, defaultOccurrence);
        assertThat(invalidFormat.timeWindow).isEqualTo(Long.MAX_VALUE);
        assertThat(invalidFormat.eventNumOccurrence).isEqualTo(Integer.MAX_VALUE);

        DataConfigManager.EventFrequency invalidRange = mDataConfigManagerUT
                .parseSlidingWindowCounterThreshold(
                        Long.MAX_VALUE + "," + Long.MAX_VALUE, defaultTimeWindow,
                        defaultOccurrence);
        assertThat(invalidRange.timeWindow).isEqualTo(defaultValue.timeWindow);
        assertThat(invalidRange.eventNumOccurrence).isEqualTo(defaultValue.eventNumOccurrence);

        DataConfigManager.EventFrequency invalidFormat2 = mDataConfigManagerUT
                .parseSlidingWindowCounterThreshold("", defaultTimeWindow, defaultOccurrence);
        assertThat(invalidFormat2.timeWindow).isEqualTo(defaultValue.timeWindow);
        assertThat(invalidFormat2.eventNumOccurrence).isEqualTo(defaultValue.eventNumOccurrence);

        DataConfigManager.EventFrequency invalidFormat3 = mDataConfigManagerUT
                .parseSlidingWindowCounterThreshold(null, defaultTimeWindow, defaultOccurrence);
        assertThat(invalidFormat3.timeWindow).isEqualTo(defaultValue.timeWindow);
        assertThat(invalidFormat3.eventNumOccurrence).isEqualTo(defaultValue.eventNumOccurrence);
    }

    @Test
    public void testParseAutoDataSwitchScoreTable() {
        SignalStrength signalStrength = mock(SignalStrength.class);
        int tolerance = 100;
        PersistableBundle auto_data_switch_rat_signal_score_string_bundle = new PersistableBundle();
        auto_data_switch_rat_signal_score_string_bundle.putIntArray(
                "NR_NSA_MMWAVE", new int[]{10000, 10227, 12488, 15017, 15278});
        auto_data_switch_rat_signal_score_string_bundle.putIntArray(
                "LTE", new int[]{-3731, 5965, 8618, 11179, 13384});
        mBundle.putPersistableBundle(
                CarrierConfigManager.KEY_AUTO_DATA_SWITCH_RAT_SIGNAL_SCORE_BUNDLE,
                auto_data_switch_rat_signal_score_string_bundle);

        mContextFixture.putIntResource(com.android.internal.R.integer
                .auto_data_switch_score_tolerance, tolerance);

        mDataConfigManagerUT.sendEmptyMessage(1/*EVENT_CARRIER_CONFIG_CHANGED*/);
        processAllMessages();

        assertThat(mDataConfigManagerUT.getAutoDataSwitchScoreTolerance()).isEqualTo(tolerance);

        // Verify NSA_MMWAVE
        doReturn(SignalStrength.SIGNAL_STRENGTH_POOR).when(signalStrength).getLevel();
        assertThat(mDataConfigManagerUT.getAutoDataSwitchScore(new TelephonyDisplayInfo(
                        TelephonyManager.NETWORK_TYPE_LTE,
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED, false/*isRoaming*/,
                        false/*isNtn*/, false/*isSatelliteConstrainedDataStatus*/),
                signalStrength)).isEqualTo(10227);
        // Verify if entry contains any invalid negative scores, should yield 0.
        doReturn(SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN).when(signalStrength).getLevel();
        assertThat(mDataConfigManagerUT.getAutoDataSwitchScore(new TelephonyDisplayInfo(
                        TelephonyManager.NETWORK_TYPE_LTE,
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, false/*isRoaming*/,
                        false/*isNtn*/, false/*isSatelliteConstrainedDataStatus*/),
                signalStrength))
                .isEqualTo(0/*OUT_OF_SERVICE_AUTO_DATA_SWITCH_SCORE*/);
        // Verify non-existent entry should yield -1
        doReturn(SignalStrength.SIGNAL_STRENGTH_POOR).when(signalStrength).getLevel();
        assertThat(mDataConfigManagerUT.getAutoDataSwitchScore(new TelephonyDisplayInfo(
                        TelephonyManager.NETWORK_TYPE_EDGE,
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE, false/*isRoaming*/,
                        false/*isNtn*/, false/*isSatelliteConstrainedDataStatus*/),
                signalStrength))
                .isEqualTo(0/*OUT_OF_SERVICE_AUTO_DATA_SWITCH_SCORE*/);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testGetMeteredNetworkCapabilities_CarrierConfigFallback() {
        mBundle.putStringArray(CarrierConfigManager.KEY_CARRIER_METERED_APN_TYPES_STRINGS,
                new String[]{ApnSetting.TYPE_MMS_STRING, ApnSetting.TYPE_DEFAULT_STRING});
        mBundle.putStringArray(CarrierConfigManager.KEY_CARRIER_METERED_ROAMING_APN_TYPES_STRINGS,
                new String[]{ApnSetting.TYPE_SUPL_STRING, ApnSetting.TYPE_MCX_STRING});

        // Trigger the Carrier Config update to populate mMeteredApnTypes
        mDataConfigManagerUT.sendEmptyMessage(1 /* EVENT_CARRIER_CONFIG_CHANGED */);
        processAllMessages();

        // Setup DataConfig to return NULL (Missing Configuration)
        // We simulate a valid DataConfig object that has no entry for this carrier/metering,
        // returning null to signal "use default/fallback".
        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setVersion(1)
                .build();
        DataConfig dataConfig = new DataConfig(proto);

        ConfigParser mockParser = mock(DataConfigParser.class);
        doReturn(dataConfig).when(mockParser).getConfig();

        // Trigger the "Config Update" callback manually
        ArgumentCaptor<ConfigProviderAdaptor.Callback> callbackCaptor =
                ArgumentCaptor.forClass(ConfigProviderAdaptor.Callback.class);
        verify(mMockConfigReceiver).registerCallback(any(), callbackCaptor.capture());
        callbackCaptor.getValue().onChanged(mockParser);

        // Verify Home Capabilities
        // Expected: CarrierConfig (MMS, INTERNET) + Hardcoded Defaults (BANDWIDTH, LATENCY,
        // UNIFIED)
        Set<Integer> homeCaps = mDataConfigManagerUT.getMeteredNetworkCapabilities(
                false /* isRoaming */);

        assertThat(homeCaps).contains(NetworkCapabilities.NET_CAPABILITY_MMS);
        assertThat(homeCaps).contains(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        // Defaults should be added because DataConfig returned null
        assertThat(homeCaps).contains(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
        assertThat(homeCaps).contains(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
        assertThat(homeCaps).contains(DataUtils.NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS);

        // Verify Roaming Capabilities
        // Expected: CarrierConfig (SUPL, MCX) + Hardcoded Defaults
        Set<Integer> roamingCaps = mDataConfigManagerUT.getMeteredNetworkCapabilities(
                true /* isRoaming */);

        assertThat(roamingCaps).contains(NetworkCapabilities.NET_CAPABILITY_SUPL);
        assertThat(roamingCaps).contains(NetworkCapabilities.NET_CAPABILITY_MCX);
        // Defaults
        assertThat(roamingCaps).contains(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
    }

    private void sendConfigUpdate(TelephonyConfigData.DataConfigProto proto) {
        // Create a real DataConfig from the proto
        DataConfig dataConfig = new DataConfig(proto);

        // Capture the callback registered by DataConfigManager
        ArgumentCaptor<ConfigProviderAdaptor.Callback> captor =
                ArgumentCaptor.forClass(ConfigProviderAdaptor.Callback.class);
        verify(mMockConfigReceiver).registerCallback(any(), captor.capture());

        // Mock the parser to return our new DataConfig
        ConfigParser parser = mock(DataConfigParser.class);
        doReturn(dataConfig).when(parser).getConfig();

        // Trigger the callback
        captor.getValue().onChanged(parser);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testDynamicMapping_NetworkToConnection() {
        // Setup: Carrier 1 maps INTERNET(12) -> SUPL(2) (Just for testing override)
        // Also map a custom capability 99 -> 100
        int customNetCap = 99;
        int customConnCap = 100;
        String rule1 = NetworkCapabilities.NET_CAPABILITY_INTERNET + ":"
                + TrafficDescriptor.CONNECTION_CAPABILITY_SUPL + ":false";
        String rule2 = customNetCap + ":" + customConnCap + ":false";

        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setVersion(1)
                .setConnectionCapabilityConfigs(
                        TelephonyConfigData.ConnectionCapabilityConfig.newBuilder()
                                .addCarrierConnectionCapabilityConfigs(
                                        TelephonyConfigData.ConnectionCapabilityMap.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                .addRules(rule1)
                                                .addRules(rule2)
                                                .build())
                                .build())
                .build();

        sendConfigUpdate(proto);

        // 1. Verify Dynamic Override (Internet -> Supl)
        int result = mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET);
        assertThat(result).isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_SUPL);

        // 2. Verify Custom Mapping (99 -> 100)
        result = mDataConfigManagerUT.networkCapabilityToConnectionCapability(customNetCap);
        assertThat(result).isEqualTo(customConnCap);

        // 3. Verify Fallback to DataUtils (MMS -> MMS)
        // Since MMS wasn't in our config, it should fall back to static logic
        result = mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_MMS);
        assertThat(result).isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_MMS);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testDynamicMapping_ConnectionToNetwork() {
        // Setup: Carrier 1 maps INTERNET(12) -> SUPL(2)
        String rule = NetworkCapabilities.NET_CAPABILITY_INTERNET + ":"
                + TrafficDescriptor.CONNECTION_CAPABILITY_SUPL + ":false";
        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setVersion(1)
                .setConnectionCapabilityConfigs(
                        TelephonyConfigData.ConnectionCapabilityConfig.newBuilder()
                                .addCarrierConnectionCapabilityConfigs(
                                        TelephonyConfigData.ConnectionCapabilityMap.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                .addRules(rule)
                                                .build())
                                .build())
                .build();

        sendConfigUpdate(proto);

        // 1. Verify Reverse Lookup (SUPL -> INTERNET)
        int result = mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_SUPL);
        assertThat(result).isEqualTo(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        // 2. Verify Fallback (MMS -> MMS)
        result = mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_MMS);
        assertThat(result).isEqualTo(NetworkCapabilities.NET_CAPABILITY_MMS);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testIsApnMatchedRequired() {
        // Rule: INTERNET -> INTERNET : ApnRequired = TRUE
        String rule = NetworkCapabilities.NET_CAPABILITY_INTERNET + ":"
                + TrafficDescriptor.CONNECTION_CAPABILITY_INTERNET + ":true";
        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setVersion(1)
                .setConnectionCapabilityConfigs(
                        TelephonyConfigData.ConnectionCapabilityConfig.newBuilder()
                                .addCarrierConnectionCapabilityConfigs(
                                        TelephonyConfigData.ConnectionCapabilityMap.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                .addRules(rule)
                                                .build())
                                .build())
                .build();

        sendConfigUpdate(proto);

        // Verify True from Config
        assertThat(mDataConfigManagerUT.isApnMatchedRequired(
                NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .isTrue();

        // Verify Default False (MMS not in config)
        assertThat(
                mDataConfigManagerUT.isApnMatchedRequired(NetworkCapabilities.NET_CAPABILITY_MMS))
                .isFalse();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testGetMeteredNetworkCapabilities_DynamicAndRoaming() {
        // Setup:
        // Home: INTERNET
        // Roaming: MMS
        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setHomeMeteredCapabilityConfigs(
                        TelephonyConfigData.MeteredCapabilityConfig.newBuilder()
                                .addCarrierMeteredCapabilityConfigs(
                                        TelephonyConfigData.MeteredCapabilities.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                .addCapabilityIds(
                                                        NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                                .build())
                                .build())
                .setRoamMeteredCapabilityConfigs(
                        TelephonyConfigData.MeteredCapabilityConfig.newBuilder()
                                .addCarrierMeteredCapabilityConfigs(
                                        TelephonyConfigData.MeteredCapabilities.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                .addCapabilityIds(
                                                        NetworkCapabilities.NET_CAPABILITY_MMS)
                                                .build())
                                .build())
                .build();

        sendConfigUpdate(proto);

        // Verify Home (Should be INTERNET only)
        Set<Integer> homeCaps = mDataConfigManagerUT.getMeteredNetworkCapabilities(
                false /* isRoaming */);
        assertThat(homeCaps).containsExactly(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        // Verify Roaming (Should be MMS only)
        Set<Integer> roamCaps = mDataConfigManagerUT.getMeteredNetworkCapabilities(
                true /* isRoaming */);
        assertThat(roamCaps).containsExactly(NetworkCapabilities.NET_CAPABILITY_MMS);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testGetMeteredNetworkCapabilities_DefaultFallback() {
        // No update sent (DataConfig is null or empty)

        Set<Integer> defaults = mDataConfigManagerUT.getMeteredNetworkCapabilities(false);

        assertThat(defaults).contains(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
        assertThat(defaults).contains(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testGetMeteredNetworkCapabilities_ExplicitEmpty() {
        // Setup: Carrier explicitly sets empty list for Home metering
        // This simulates a carrier wanting NO capabilities to be metered.
        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setHomeMeteredCapabilityConfigs(
                        TelephonyConfigData.MeteredCapabilityConfig.newBuilder()
                                .addCarrierMeteredCapabilityConfigs(
                                        TelephonyConfigData.MeteredCapabilities.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                // No addCapabilityIds() called -> Empty List
                                                .build())
                                .build())
                .build();

        sendConfigUpdate(proto);

        // Verify Home returns EMPTY set
        Set<Integer> homeCaps =
                mDataConfigManagerUT.getMeteredNetworkCapabilities(false /* isRoaming */);
        assertThat(homeCaps).isEmpty();

        // Verify that Hardcoded Defaults were NOT added
        assertThat(homeCaps).doesNotContain(
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
        assertThat(homeCaps).doesNotContain(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testGetMeteredNetworkCapabilities_NoConfigFallback() {
        // Setup: Proto has NO metered config sections
        // (simulate standard update without metered changes)
        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setVersion(1)
                // .setHomeMeteredCapabilityConfigs is NOT called
                .build();

        sendConfigUpdate(proto);

        // Verify returns Hardcoded Defaults
        Set<Integer> homeCaps =
                mDataConfigManagerUT.getMeteredNetworkCapabilities(false /* isRoaming */);

        assertThat(homeCaps).contains(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
        assertThat(homeCaps).contains(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
        // Note: Check for UNIFIED_COMMUNICATIONS if available in your test SDK
        assertThat(homeCaps).contains(DataUtils.NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testGetMeteredNetworkCapabilities_CarrierVsDefaultProto() {
        // Setup:
        // Default in Proto: [MMS]
        // Carrier 1 (Current) in Proto: [INTERNET]
        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setHomeMeteredCapabilityConfigs(
                        TelephonyConfigData.MeteredCapabilityConfig.newBuilder()
                                .setDefaultMeteredCapabilityConfig(
                                        TelephonyConfigData.MeteredCapabilities.newBuilder()
                                                .addCapabilityIds(
                                                        NetworkCapabilities.NET_CAPABILITY_MMS)
                                                .build())
                                .addCarrierMeteredCapabilityConfigs(
                                        TelephonyConfigData.MeteredCapabilities.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                .addCapabilityIds(
                                                        NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                                .build())
                                .build())
                .build();

        sendConfigUpdate(proto);

        // 1. Verify Current Carrier gets Carrier Specific Config [INTERNET]
        Set<Integer> carrierCaps = mDataConfigManagerUT.getMeteredNetworkCapabilities(false);
        assertThat(carrierCaps).containsExactly(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        // 2. Simulate switching to a different carrier (ID 999)
        doReturn(999).when(mPhone).getCarrierId();

        // Verify Unknown Carrier gets Default Proto Config [MMS]
        Set<Integer> defaultCaps = mDataConfigManagerUT.getMeteredNetworkCapabilities(false);
        assertThat(defaultCaps).containsExactly(NetworkCapabilities.NET_CAPABILITY_MMS);
    }

    @Test
    public void testNetworkCapabilityToConnectionCapabilityStatic() {
        // Test valid mappings
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_MMS))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_MMS);
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_SUPL))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_SUPL);
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_IMS))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_IMS);
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_INTERNET);
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_REAL_TIME_INTERACTIVE);
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_DOWNLINK_STREAMING);
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                DataUtils.NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_UNIFIED_COMMUNICATIONS);

        // Test a capability that is not explicitly mapped
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(
                NetworkCapabilities.NET_CAPABILITY_FOTA))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_UNKNOWN);

        // Test with an invalid capability value
        assertThat(mDataConfigManagerUT.networkCapabilityToConnectionCapability(9999))
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_UNKNOWN);
    }

    @Test
    public void testConnectionCapabilityToNetworkCapabilityStatic() {
        // Test valid mappings
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_MMS))
                .isEqualTo(NetworkCapabilities.NET_CAPABILITY_MMS);
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_SUPL))
                .isEqualTo(NetworkCapabilities.NET_CAPABILITY_SUPL);
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_IMS))
                .isEqualTo(NetworkCapabilities.NET_CAPABILITY_IMS);
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_INTERNET))
                .isEqualTo(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_REAL_TIME_INTERACTIVE))
                .isEqualTo(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY);
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_DOWNLINK_STREAMING))
                .isEqualTo(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH);
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_UNIFIED_COMMUNICATIONS))
                .isEqualTo(DataUtils.NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS);

        // Test the default unknown case
        assertThat(mDataConfigManagerUT.connectionCapabilityToNetworkCapability(
                TrafficDescriptor.CONNECTION_CAPABILITY_UNKNOWN))
                .isEqualTo(-1);
    }

    @Test
    public void testIsApnMatchedRequired_Fallback() {
        // 1. Setup Dynamic Config with a rule for MMS, but NOT for INTERNET
        // This allows us to verify that MMS uses the dynamic config, while INTERNET falls back.
        String rule = NetworkCapabilities.NET_CAPABILITY_MMS + ":"
                + TrafficDescriptor.CONNECTION_CAPABILITY_MMS + ":false";

        TelephonyConfigData.DataConfigProto proto = TelephonyConfigData.DataConfigProto.newBuilder()
                .setVersion(1)
                .setConnectionCapabilityConfigs(
                        TelephonyConfigData.ConnectionCapabilityConfig.newBuilder()
                                .addCarrierConnectionCapabilityConfigs(
                                        TelephonyConfigData.ConnectionCapabilityMap.newBuilder()
                                                .setCarrierId(mPhone.getCarrierId())
                                                .addRules(rule)
                                                .build())
                                .build())
                .build();

        // Update the original object first so it has the config
        sendConfigUpdate(proto);

        // 2. Set CarrierConfig to TRUE
        mBundle.putBoolean(CarrierConfigManager.KEY_APN_MATCHED_REQUIRED, true);
        mDataConfigManagerUT.sendEmptyMessage(1/*EVENT_CARRIER_CONFIG_CHANGED*/);
        processAllMessages();

        // Verify INTERNET (not in dynamic config) falls back to CarrierConfig (TRUE)
        assertThat(mDataConfigManagerUT.isApnMatchedRequired(
                NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .isTrue();

        // 3. Set CarrierConfig to FALSE
        mBundle.putBoolean(CarrierConfigManager.KEY_APN_MATCHED_REQUIRED, false);
        mDataConfigManagerUT.sendEmptyMessage(1/*EVENT_CARRIER_CONFIG_CHANGED*/);
        processAllMessages();

        // Verify INTERNET (not in dynamic config) falls back to CarrierConfig (FALSE)
        assertThat(mDataConfigManagerUT.isApnMatchedRequired(
                NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .isFalse();

        // 4. Verify MMS (present in dynamic config as 'false') respects the config
        mBundle.putBoolean(CarrierConfigManager.KEY_APN_MATCHED_REQUIRED, true);
        mDataConfigManagerUT.sendEmptyMessage(1/*EVENT_CARRIER_CONFIG_CHANGED*/);
        processAllMessages();

        assertThat(mDataConfigManagerUT.isApnMatchedRequired(
                NetworkCapabilities.NET_CAPABILITY_MMS))
                .isFalse();
    }
}
