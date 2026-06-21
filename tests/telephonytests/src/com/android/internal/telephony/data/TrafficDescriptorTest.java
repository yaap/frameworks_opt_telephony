/*
 * Copyright 2022 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;

import android.os.Parcel;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telephony.Rlog;
import android.telephony.data.TrafficDescriptor;
import android.telephony.data.TrafficDescriptor.OsAppId;

import com.android.internal.telephony.flags.Flags;

import org.junit.Rule;
import org.junit.Test;

import java.math.BigInteger;
import java.util.UUID;

public class TrafficDescriptorTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testEnterpriseOsAppId() {
        for (int i = 1; i <= 5; i++) {
            OsAppId osAppId = new OsAppId(OsAppId.ANDROID_OS_ID, "ENTERPRISE", i);
            byte[] rawBytes = osAppId.getBytes();
            Rlog.d("TrafficDescriptorTest", "rawBytes=" + new BigInteger(1, rawBytes).toString(16)
                    + ", osAppId=" + osAppId);
            assertThat(new OsAppId(rawBytes)).isEqualTo(osAppId);
            assertThat(osAppId.getOsId()).isEqualTo(OsAppId.ANDROID_OS_ID);
            assertThat(osAppId.getAppId()).isEqualTo("ENTERPRISE");
            assertThat(osAppId.getDifferentiator()).isEqualTo(i);
        }
    }

    @Test
    public void testUrllcOsAppId() {
        OsAppId osAppId = new OsAppId(OsAppId.ANDROID_OS_ID, "PRIORITIZE_LATENCY", 1);
        byte[] rawBytes = osAppId.getBytes();
        Rlog.d("TrafficDescriptorTest", "rawBytes=" + new BigInteger(1, rawBytes).toString(16)
                + ", osAppId=" + osAppId);
        assertThat(new OsAppId(rawBytes)).isEqualTo(osAppId);
        assertThat(osAppId.getOsId()).isEqualTo(OsAppId.ANDROID_OS_ID);
        assertThat(osAppId.getAppId()).isEqualTo("PRIORITIZE_LATENCY");
        assertThat(osAppId.getDifferentiator()).isEqualTo(1);
    }

    @Test
    public void testEmbbOsAppId() {
        OsAppId osAppId = new OsAppId(OsAppId.ANDROID_OS_ID, "PRIORITIZE_BANDWIDTH", 1);
        byte[] rawBytes = osAppId.getBytes();
        Rlog.d("TrafficDescriptorTest", "rawBytes=" + new BigInteger(1, rawBytes).toString(16)
                + ", osAppId=" + osAppId);
        assertThat(new OsAppId(rawBytes)).isEqualTo(osAppId);
        assertThat(osAppId.getOsId()).isEqualTo(OsAppId.ANDROID_OS_ID);
        assertThat(osAppId.getAppId()).isEqualTo("PRIORITIZE_BANDWIDTH");
        assertThat(osAppId.getDifferentiator()).isEqualTo(1);
    }

    @Test
    public void testUfcOsAppId() {
        OsAppId osAppId = new OsAppId(OsAppId.ANDROID_OS_ID,
                "PRIORITIZE_UNIFIED_COMMUNICATIONS", 1);
        byte[] rawBytes = osAppId.getBytes();
        Rlog.d("TrafficDescriptorTest", "rawBytes=" + new BigInteger(1, rawBytes).toString(16)
                + ", osAppId=" + osAppId);
        assertThat(new OsAppId(rawBytes)).isEqualTo(osAppId);
        assertThat(osAppId.getOsId()).isEqualTo(OsAppId.ANDROID_OS_ID);
        assertThat(osAppId.getAppId()).isEqualTo("PRIORITIZE_UNIFIED_COMMUNICATIONS");
        assertThat(osAppId.getDifferentiator()).isEqualTo(1);
    }

    @Test
    public void testInvalidOsId() {
        OsAppId osAppId = new OsAppId(UUID.fromString("91b7f6fb-5069-4e29-af83-50e942e9b1c3"),
                "ENTERPRISE", 1);
        // IllegalArgumentException is expected when OS id is not Android
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficDescriptor.Builder()
                        .setDataNetworkName("DNN")
                        .setOsAppId(osAppId.getBytes())
                        .build());
    }

    @Test
    public void testInvalidAppId() {
        OsAppId osAppId = new OsAppId(OsAppId.ANDROID_OS_ID, "FOO", 1);
        // IllegalArgumentException is expected when App id is not in the allowed this.
        assertThrows(IllegalArgumentException.class,
                () -> new TrafficDescriptor.Builder()
                        .setDataNetworkName("DNN")
                        .setOsAppId(osAppId.getBytes())
                        .build());
    }

    @Test
    public void testInvalidDifferentiator() {
        // IllegalArgumentException is expected when App id is not in the allowed this.
        assertThrows(IllegalArgumentException.class,
                () -> new OsAppId(OsAppId.ANDROID_OS_ID, "ENTERPRISE", 0));
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityBuilderAndGetter_flagOn() {
        TrafficDescriptor td1 =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();
        assertThat(td1.getConnectionCapability())
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_IMS);

        TrafficDescriptor td2 =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_MMS)
                        .build();
        assertThat(td2.getConnectionCapability())
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_MMS);

        TrafficDescriptor td3 =
                new TrafficDescriptor.Builder()
                        .setDataNetworkName("TEST_DNN")
                        .build();
        assertThat(td3.getConnectionCapability())
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_UNKNOWN);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityEqualsAndHashCode_flagOn() {
        TrafficDescriptor td1 =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();
        TrafficDescriptor td2 =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();
        TrafficDescriptor td3 =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_MMS)
                        .build();

        assertThat(td1).isEqualTo(td2);
        assertThat(td1.hashCode()).isEqualTo(td2.hashCode());
        assertThat(td1).isNotEqualTo(td3);
        assertThat(td1.hashCode()).isNotEqualTo(td3.hashCode());
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityParcelable_flagOn() {
        TrafficDescriptor td =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_SUPL)
                        .build();

        Parcel parcel = Parcel.obtain();
        td.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        TrafficDescriptor fromParcel = TrafficDescriptor.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(fromParcel).isEqualTo(td);
        assertThat(fromParcel.getConnectionCapability())
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_SUPL);
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityToString_flagOn() {
        TrafficDescriptor td =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();
        assertThat(td.toString()).contains("ConnectionCapability=1");
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityBuilderValidation_flagOn() {
        // Only ConnectionCapability set is OK when flag is on
        TrafficDescriptor td =
                new TrafficDescriptor.Builder()
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_MMS)
                        .build();
        assertThat(td).isNotNull();

        // All null still fails
        assertThrows(IllegalArgumentException.class, () -> new TrafficDescriptor.Builder().build());
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityBuilderAndGetter_flagOff() {
        TrafficDescriptor td1 =
                new TrafficDescriptor.Builder()
                        .setDataNetworkName("TEST_DNN")
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();
        assertThat(td1.getConnectionCapability())
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_UNKNOWN);
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityEqualsAndHashCode_flagOff() {
        TrafficDescriptor td1 =
                new TrafficDescriptor.Builder()
                        .setDataNetworkName("TEST_DNN")
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();
        TrafficDescriptor td2 =
                new TrafficDescriptor.Builder()
                        .setDataNetworkName("TEST_DNN")
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();

        assertThat(td1).isEqualTo(td2); // Equal because ConnectionCapability is ignored
        assertThat(td1.hashCode()).isEqualTo(td2.hashCode());
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityParcelable_flagOff() {
        TrafficDescriptor td =
                new TrafficDescriptor.Builder()
                        .setDataNetworkName("TEST_DNN")
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_SUPL)
                        .build();

        Parcel parcel = Parcel.obtain();
        td.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        TrafficDescriptor fromParcel = TrafficDescriptor.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertThat(fromParcel.getConnectionCapability())
                .isEqualTo(TrafficDescriptor.CONNECTION_CAPABILITY_UNKNOWN);
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityToString_flagOff() {
        TrafficDescriptor td =
                new TrafficDescriptor.Builder()
                        .setDataNetworkName("TEST_DNN")
                        .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_IMS)
                        .build();
        assertThat(td.toString()).doesNotContain("ConnectionCapability");
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_TRAFFIC_DESCRIPTOR_CONNECTION_CAPABILITY)
    public void testConnectionCapabilityBuilderValidation_flagOff() {
        // Only ConnectionCapability set fails when flag is off
        assertThrows(IllegalArgumentException.class, () -> new TrafficDescriptor.Builder()
                .setConnectionCapability(TrafficDescriptor.CONNECTION_CAPABILITY_MMS).build());

        // All null still fails
        assertThrows(IllegalArgumentException.class, () -> new TrafficDescriptor.Builder().build());
    }
}
