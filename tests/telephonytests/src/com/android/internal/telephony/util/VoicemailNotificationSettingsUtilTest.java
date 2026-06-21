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

package com.android.internal.telephony.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;

import com.android.internal.telephony.TelephonyTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class VoicemailNotificationSettingsUtilTest extends TelephonyTest {

    @Mock
    private NotificationManager mNotificationManager;

    private NotificationChannel mNotificationChannel;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        MockitoAnnotations.initMocks(this);

        mNotificationChannel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_DEFAULT);

        mContextFixture.setSystemService(Context.NOTIFICATION_SERVICE, mNotificationManager);
    }

    @After
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testGetRingtoneUri_channelNull() {
        doReturn(null).when(mNotificationManager).getNotificationChannel(anyString());

        Uri uri = VoicemailNotificationSettingsUtil.getRingtoneUri(mContext);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, uri);
    }

    @Test
    public void testGetRingtoneUri_channelExistsWithSound() {
        Uri soundUri = Uri.parse("content://media/external/audio/media/1");
        mNotificationChannel.setSound(soundUri, null);
        doReturn(mNotificationChannel).when(mNotificationManager).getNotificationChannel(
                anyString());

        Uri uri = VoicemailNotificationSettingsUtil.getRingtoneUri(mContext);
        assertEquals(soundUri, uri);
    }

    @Test
    public void testGetRingtoneUri_channelExistsWithoutSound() {
        mNotificationChannel.setSound(null, null);
        doReturn(mNotificationChannel).when(mNotificationManager).getNotificationChannel(
                anyString());

        Uri uri = VoicemailNotificationSettingsUtil.getRingtoneUri(mContext);
        assertNull(uri);
    }
}
