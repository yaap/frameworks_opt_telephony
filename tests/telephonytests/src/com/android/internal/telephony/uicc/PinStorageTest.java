/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.internal.telephony.uicc;

import static android.security.Flags.FLAG_AUTO_SIM_PIN_MANAGEMENT;
import static android.security.keystore.KeyProperties.BLOCK_MODE_GCM;
import static android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_AES;
import static android.security.keystore.KeyProperties.PURPOSE_DECRYPT;
import static android.security.keystore.KeyProperties.PURPOSE_ENCRYPT;

import static com.android.internal.telephony.uicc.IccCardStatus.PinState.PINSTATE_ENABLED_VERIFIED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.WorkSource;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyTest;
import com.android.internal.telephony.nano.StoredPinProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.List;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class PinStorageTest extends TelephonyTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String ICCID_1 = "89010003006562472370";
    private static final String ICCID_2 = "89010003006562472399";
    private static final String ICCID_3 = "8981100022152967705F";
    private static final String PIN_1 = "1234";
    private static final String PIN_2 = "2345";
    private static final String PIN_3 = "3456";

    private static final StoredPinProto.PlatformManagedPin PLATFORM_PIN_1 =
            new StoredPinProto.PlatformManagedPin();
    private static final StoredPinProto.PlatformManagedPin PLATFORM_PIN_2 =
            new StoredPinProto.PlatformManagedPin();
    private static final StoredPinProto.PlatformManagedPin PLATFORM_PIN_3 =
            new StoredPinProto.PlatformManagedPin();

    private static final String ICCID_INVALID = "1234";
    private static final String PACKAGE_NAME = "com.package.name";
    private static final int UID = -1;
    private static final WorkSource sWorkSource = new WorkSource(UID, PACKAGE_NAME);

    private int mBootCount;
    private int mSimulatedRebootsCount;
    private PinStorage mPinStorage;
    private PersistableBundle mBundle;

    // mocks
    private CarrierConfigManager.CarrierConfigChangeListener mCarrierConfigChangeListener;

    private void simulateReboot() throws Exception {
        mSimulatedRebootsCount++;
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.BOOT_COUNT, mBootCount + mSimulatedRebootsCount);

        createPinStorageAndCaptureListener();
    }

    private void createPinStorageAndCaptureListener() throws Exception {
        // Capture listener to emulate the carrier config change notification used later
        ArgumentCaptor<CarrierConfigManager.CarrierConfigChangeListener> listenerArgumentCaptor =
                ArgumentCaptor.forClass(CarrierConfigManager.CarrierConfigChangeListener.class);
        mPinStorage = new PinStorage(mContext, Looper.myLooper(), mFeatureFlags);
        processAllMessages();
        mPinStorage.mShortTermSecretKeyDurationMinutes = 0;
        verify(mCarrierConfigManager, atLeastOnce()).registerCarrierConfigChangeListener(any(),
                listenerArgumentCaptor.capture());
        mCarrierConfigChangeListener = listenerArgumentCaptor.getAllValues().get(0);
    }

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        mCarrierConfigChangeListener = Mockito.mock(
                CarrierConfigManager.CarrierConfigChangeListener.class);

        mBundle = mContextFixture.getCarrierConfigBundle();
        when(mCarrierConfigManager.getConfigForSubId(anyInt(), any())).thenReturn(mBundle);

        // Store boot count, so that correct value can be restored at the end.
        mBootCount = Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        mSimulatedRebootsCount = 0;

        // Clear shared preferences.
        PreferenceManager.getDefaultSharedPreferences(InstrumentationRegistry.getContext())
                .edit().clear().commit();
        // Enable PIN storage in resources
        mContextFixture.putBooleanResource(
                R.bool.config_allow_pin_storage_for_unattended_reboot, true);
        // Remaining setup
        doReturn(ICCID_1).when(mPhone).getFullIccSerialNumber();
        // Simulate the device is not secure by default
        when(mKeyguardManager.isDeviceSecure()).thenReturn(false);
        when(mKeyguardManager.isDeviceLocked()).thenReturn(false);

        PLATFORM_PIN_1.iccid = ICCID_1;
        PLATFORM_PIN_1.pin = PIN_1;
        PLATFORM_PIN_2.iccid = ICCID_2;
        PLATFORM_PIN_2.pin = PIN_2;
        PLATFORM_PIN_3.iccid = ICCID_3;
        PLATFORM_PIN_3.pin = PIN_3;

        createPinStorageAndCaptureListener();
    }

    @After
    public void tearDown() throws Exception {
        mPinStorage = null;
        // Restore boot count
        if (mBootCount == -1) {
            Settings.Global.resetToDefaults(
                    mContext.getContentResolver(), Settings.Global.BOOT_COUNT);
        } else {
            Settings.Global.putInt(
                    mContext.getContentResolver(), Settings.Global.BOOT_COUNT, mBootCount);
        }
        super.tearDown();
    }

    @Test
    public void storePin_withoutReboot_pinCannotBeRetrieved() {
        mPinStorage.storePin("1234", 0);

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_normalReboot_pinCannotBeRetrieved() throws Exception {
        mPinStorage.storePin("1234", 0);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_crash_pinCannotBeRetrieved() throws Exception {
        mPinStorage.storePin("1234", 0);

        // Simulate crash
        mPinStorage = new PinStorage(mContext, Looper.myLooper(), mFeatureFlags);
        processAllMessages();
        mPinStorage.mShortTermSecretKeyDurationMinutes = 0;

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_unattendedReboot_pinCanBeRetrievedOnce() throws Exception {
        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        processAllMessages();
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        // PIN can be retrieved only once after unattended reboot
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("1234");
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_unattendedReboot_deviceIsLocked() throws Exception {
        // Simulate the device is still locked
        when(mKeyguardManager.isDeviceSecure()).thenReturn(true);
        when(mKeyguardManager.isDeviceLocked()).thenReturn(true);
        simulateReboot();

        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_ERROR);

        simulateReboot();

        // PIN cannot  be retrieved
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_unattendedReboot_pinIsRemovedAfterDelay() throws Exception {
        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        // Move time forward by 60 seconds
        moveTimeForward(60000);
        processAllMessages();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");

        // Simulate a second unattended reboot to make sure that PIN was deleted.
        result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_unattendedRebootNotDone_pinCannotBeRetrieved() throws Exception {
        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        processAllMessages();
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        // Move time forward by 60 seconds before simulating reboot
        moveTimeForward(60000);
        processAllMessages();
        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_unattendedReboot_iccidChange() throws Exception {
        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        // Switch to a different ICCID in the device after the reboot
        doReturn(ICCID_2).when(mPhone).getFullIccSerialNumber();

        assertThat(mPinStorage.getPin(0, ICCID_2)).isEqualTo("");

        // Switch back to the initial ICCID to make sure that PIN was deleted.
        doReturn(ICCID_1).when(mPhone).getFullIccSerialNumber();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void clearPin_pinCannotBeRetrieved() throws Exception {
        mPinStorage.storePin("1234", 0);
        mPinStorage.clearPin(0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_pinChanged_pinIsUpdated() throws Exception {
        mPinStorage.storePin("1234", 0);
        mPinStorage.storePin("5678", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        processAllMessages();
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("5678");
    }

    @Test
    public void storePin_pinTooShort_pinIsNotStored() throws Exception {
        mPinStorage.storePin("12", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_pinTooLong_pinIsNotStored() throws Exception {
        mPinStorage.storePin("123456789", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_invalidIccid_pinIsNotStored() throws Exception {
        doReturn(ICCID_INVALID).when(mPhone).getFullIccSerialNumber();

        mPinStorage.storePin("1234", 0);
        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_INVALID)).isEqualTo("");
    }

    @Test
    public void storePin_disabledInResources_pinIsNotStored() throws Exception {
        mContextFixture.putBooleanResource(
                R.bool.config_allow_pin_storage_for_unattended_reboot, false);

        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_disabledInResources_containsSimWithPinEnabledAndVerified()
            throws Exception {
        mContextFixture.putBooleanResource(
                R.bool.config_allow_pin_storage_for_unattended_reboot, false);

        when(mUiccController.getUiccProfileForPhone(anyInt())).thenReturn(mUiccProfile);
        when(mUiccCardApplication3gpp.getPin1State()).thenReturn(PINSTATE_ENABLED_VERIFIED);

        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_PIN_REQUIRED);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_disabledInCarrierConfig_pinIsNotStored() throws Exception {
        PersistableBundle carrierConfigs = new PersistableBundle();
        carrierConfigs.putBoolean(
                CarrierConfigManager.KEY_STORE_SIM_PIN_FOR_UNATTENDED_REBOOT_BOOL, false);
        when(mCarrierConfigManager.getConfigForSubId(anyInt(), any())).thenReturn(carrierConfigs);

        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_changeToDisabledInCarrierConfig_pinIsRemoved() throws Exception {
        mPinStorage.storePin("1234", 0);

        // Simulate change in the carrier configuration
        PersistableBundle carrierConfigs = new PersistableBundle();
        carrierConfigs.putBoolean(
                CarrierConfigManager.KEY_STORE_SIM_PIN_FOR_UNATTENDED_REBOOT_BOOL, false);
        when(mCarrierConfigManager.getConfigForSubId(anyInt(), any())).thenReturn(carrierConfigs);
        mCarrierConfigChangeListener.onCarrierConfigChanged(0 /* slotIndex */,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                TelephonyManager.UNKNOWN_CARRIER_ID, TelephonyManager.UNKNOWN_CARRIER_ID);
        processAllMessages();

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_simIsRemoved_pinIsRemoved() throws Exception {
        mPinStorage.storePin("1234", 0);

        // SIM is removed
        final Intent intent = new Intent(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        intent.putExtra(PhoneConstants.PHONE_KEY, 0);
        intent.putExtra(TelephonyManager.EXTRA_SIM_STATE, TelephonyManager.SIM_STATE_ABSENT);
        mContext.sendBroadcast(intent);
        processAllMessages();

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @Test
    public void storePin_simReadyAfterUnattendedReboot_pinIsRemoved() throws Exception {
        mPinStorage.storePin("1234", 0);

        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        processAllMessages();
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);

        simulateReboot();

        // SIM is fully loaded before cached PIN is used.
        final Intent intent = new Intent(TelephonyManager.ACTION_SIM_APPLICATION_STATE_CHANGED);
        intent.putExtra(PhoneConstants.PHONE_KEY, 0);
        intent.putExtra(TelephonyManager.EXTRA_SIM_STATE, TelephonyManager.SIM_STATE_LOADED);
        mContext.sendBroadcast(intent);
        processAllMessages();

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_notPlatformManaged_indicatesCorrectly() {
        mPinStorage.storePin("1234", 0);

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(false);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_indicatesCorrectly() {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_simIsRemoved_stillPlatformManaged() throws Exception {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        // SIM is removed
        final Intent intent = new Intent(TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED);
        intent.putExtra(PhoneConstants.PHONE_KEY, 0);
        intent.putExtra(TelephonyManager.EXTRA_SIM_STATE, TelephonyManager.SIM_STATE_ABSENT);
        mContext.sendBroadcast(intent);
        processAllMessages();
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);

        // Assert the state removed after preparing unattended reboot.
        int result = mPinStorage.prepareUnattendedReboot(sWorkSource);
        assertThat(result).isEqualTo(TelephonyManager.PREPARE_UNATTENDED_REBOOT_SUCCESS);
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);

        // And after "rebooting".
        simulateReboot();
        processAllMessages();
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_doesNotChangeStateViaStorePin() throws Exception {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
        mPinStorage.storePin("1234", 0);
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
        simulateReboot();
        processAllMessages();
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_doesNotChangeStateViaClearPin() throws Exception {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
        mPinStorage.clearPin(0);
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
        simulateReboot();
        processAllMessages();
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_unenrollsCorrectly() throws Exception {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
        mPinStorage.clearPlatformManagedPin(0);
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(false);
        simulateReboot();
        processAllMessages();
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(false);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_indicatesCorrectlyWhenDeviceLocked() throws Exception {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);

        when(mKeyguardManager.isDeviceSecure()).thenReturn(true);
        when(mKeyguardManager.isDeviceLocked()).thenReturn(true);
        simulateReboot();
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_notPlatformManaged_cannotReadWhenDeviceLocked() throws Exception {
        mPinStorage.storePin("1234", 0);

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(false);

        when(mKeyguardManager.isDeviceSecure()).thenReturn(true);
        when(mKeyguardManager.isDeviceLocked()).thenReturn(true);
        simulateReboot();
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_canReadOnlyOnceWhenDeviceLocked() throws Exception {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
        mPinStorage.handleMessage(mPinStorage.obtainMessage(7 /* DEVICE_UNLOCKED_EVENT */));

        when(mKeyguardManager.isDeviceSecure()).thenReturn(true);
        when(mKeyguardManager.isDeviceLocked()).thenReturn(true);
        simulateReboot();
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("1234");
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void clearPin_platformManaged_indicatesCorrectly() throws Exception {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(true);
        mPinStorage.clearPlatformManagedPin(0);
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(false);
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
        // Make sure the state remains correct after a reboot.
        simulateReboot();
        assertThat(mPinStorage.isPinPlatformManaged(ICCID_1)).isEqualTo(false);
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("");
        assertThat(mPinStorage.getOldPin(ICCID_1)).isEqualTo("");
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_canReadPins() {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("1234");
        assertThat(mPinStorage.getOldPin(ICCID_1)).isEqualTo("0000");
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void storePin_platformManaged_doesNotProvidePinForOtherIccid() {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");

        // Change to the second ICCID
        doReturn(ICCID_2).when(mPhone).getFullIccSerialNumber();
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo("1234");
        assertThat(mPinStorage.getPin(0, ICCID_2)).isEqualTo("");
    }

    private SecretKey createSecretKey(String alias)
            throws InvalidAlgorithmParameterException, NoSuchAlgorithmException,
            NoSuchProviderException {
        final KeyGenerator keyGenerator =
                KeyGenerator.getInstance(KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder keyGenParameterSpec =
                new KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT | PURPOSE_DECRYPT)
                        .setBlockModes(BLOCK_MODE_GCM)
                        .setEncryptionPaddings(ENCRYPTION_PADDING_NONE);

        keyGenerator.init(keyGenParameterSpec.build());
        return keyGenerator.generateKey();
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void testEncryptAndDecryptOfPlatformManagedPins_canDecrypt()
            throws Exception {
        String keyAlias = "encryption_test";
        byte[] payload = "hello".getBytes();

        SecretKey key = createSecretKey(keyAlias);

        byte[] ciphertext = PinStorage.encryptPlatformManagedPins(key, payload);
        StoredPinProto.EncryptedPlatformManagedPins platformPins =
                StoredPinProto.EncryptedPlatformManagedPins.parseFrom(ciphertext);
        assertThat(platformPins.encryptedPlatformPins.length).isGreaterThan(0);
        assertThat(platformPins.iv.length).isGreaterThan(0);

        byte[] plaintext = PinStorage.decryptPlatformManagedPins(key, ciphertext);
        assertThat(plaintext).isEqualTo(payload);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void testEncryptAndDecryptOfPlatformManagedPins_emptyOnDecryptionFailure()
            throws GeneralSecurityException {

        String keyAlias = "decryption_test";

        SecretKey key = createSecretKey(keyAlias);

        byte[] plaintext = PinStorage.decryptPlatformManagedPins(key, "invalid".getBytes());
        assertThat(plaintext).isNull();
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void testSerializationOfIccIds() {
        String iccid1 = "8981100022152967705F";
        String iccid2 = "8981100022152961205F";
        Set<String> iccids = Set.of(iccid1, iccid2);

        String serialized = PinStorage.serializeIccidsSet(iccids);

        Set<String> deserialized = PinStorage.deserializeIccids(serialized);
        assertThat(deserialized).containsExactly(iccid1, iccid2);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void testGetPlatformManagedPinsForBackup() {
        mPinStorage.storePlatformManagedPin(0, "1234", "0000");
        assertThat(mPinStorage.getPlatformManagedPinsForBackup()).isNotEmpty();
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void testMergingOfPlatformManagedPins() {
        StoredPinProto.PlatformManagedPin platformPin2Modified =
                new StoredPinProto.PlatformManagedPin();
        platformPin2Modified.iccid = PLATFORM_PIN_2.iccid;
        platformPin2Modified.pin = "7890";

        StoredPinProto.PlatformManagedPins existingPins = new StoredPinProto.PlatformManagedPins();
        existingPins.pins = new StoredPinProto.PlatformManagedPin[]{PLATFORM_PIN_1, PLATFORM_PIN_2};

        StoredPinProto.PlatformManagedPins backupPins = new StoredPinProto.PlatformManagedPins();
        backupPins.pins =
                new StoredPinProto.PlatformManagedPin[]{platformPin2Modified, PLATFORM_PIN_3};

        List<StoredPinProto.PlatformManagedPin> merged = PinStorage.mergeExistingAndBackupPins(
                existingPins, backupPins);

        assertThat(merged).containsExactly(PLATFORM_PIN_1, PLATFORM_PIN_2, PLATFORM_PIN_3);
    }

    @RequiresFlagsEnabled(FLAG_AUTO_SIM_PIN_MANAGEMENT)
    @Test
    public void testRestorePlatformManagedPinsFromBackup() {
        mPinStorage.storePlatformManagedPin(0, PIN_1, "0000");

        StoredPinProto.PlatformManagedPin platformPin1Modified =
                new StoredPinProto.PlatformManagedPin();
        platformPin1Modified.iccid = PLATFORM_PIN_1.iccid;
        platformPin1Modified.pin = "7890";

        StoredPinProto.PlatformManagedPins pinsToRestore = new StoredPinProto.PlatformManagedPins();
        pinsToRestore.pins =
                new StoredPinProto.PlatformManagedPin[]{platformPin1Modified, PLATFORM_PIN_2,
                        PLATFORM_PIN_3};
        byte[] blobToRestore = StoredPinProto.PlatformManagedPins.toByteArray(pinsToRestore);

        mPinStorage.restorePlatformManagedPinsFromBackup(blobToRestore);
        assertThat(mPinStorage.getPin(0, ICCID_1)).isEqualTo(PIN_1);
        assertThat(mPinStorage.getPin(0, ICCID_2)).isEqualTo(PIN_2);
        assertThat(mPinStorage.getPin(0, ICCID_3)).isEqualTo(PIN_3);
    }
}
