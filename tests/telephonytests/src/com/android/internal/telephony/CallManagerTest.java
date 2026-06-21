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

package com.android.internal.telephony;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.telephony.PhoneCapability;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.telephony.flags.Flags;
import com.android.internal.telephony.imsphone.ImsPhone;
import com.android.internal.telephony.imsphone.ImsPhoneCall;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CallManagerTest extends TelephonyTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private CallManager mCallManagerUT;
    private ImsPhone mMockImsPhone;
    private GsmCdmaCall mRingingCall;
    private ImsPhoneCall mImsRingingCall;
    private GsmCdmaCall mForegroundCall;
    private ImsPhoneCall mImsForegroundCall;
    private GsmCdmaCall mBackgroundCall;
    private ImsPhoneCall mImsBackgroundCall;
    private Connection mConnection;

    @Before
    public void setUp() throws Exception {
        super.setUp(getClass().getSimpleName());
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mMockImsPhone = mock(ImsPhone.class);
        mRingingCall = mock(GsmCdmaCall.class);
        mImsRingingCall = mock(ImsPhoneCall.class);
        mForegroundCall = mock(GsmCdmaCall.class);
        mImsForegroundCall = mock(ImsPhoneCall.class);
        mBackgroundCall = mock(GsmCdmaCall.class);
        mImsBackgroundCall = mock(ImsPhoneCall.class);
        mConnection = mock(Connection.class);

        doReturn(mRingingCall).when(mPhone).getRingingCall();
        doReturn(mForegroundCall).when(mPhone).getForegroundCall();
        doReturn(mBackgroundCall).when(mPhone).getBackgroundCall();
        doReturn(0).when(mPhone).getSubId();

        doReturn(mImsRingingCall).when(mMockImsPhone).getRingingCall();
        doReturn(mImsForegroundCall).when(mMockImsPhone).getForegroundCall();
        doReturn(mImsBackgroundCall).when(mMockImsPhone).getBackgroundCall();
        doReturn(1).when(mMockImsPhone).getSubId();

        doReturn(mPhone).when(mRingingCall).getPhone();
        doReturn(mPhone).when(mForegroundCall).getPhone();
        doReturn(mPhone).when(mBackgroundCall).getPhone();

        doReturn(mMockImsPhone).when(mImsRingingCall).getPhone();
        doReturn(mMockImsPhone).when(mImsForegroundCall).getPhone();
        doReturn(mMockImsPhone).when(mImsBackgroundCall).getPhone();

        doReturn(Call.State.INCOMING).when(mRingingCall).getState();
        doReturn(Call.State.INCOMING).when(mImsRingingCall).getState();
        doReturn(Call.State.IDLE).when(mForegroundCall).getState();
        doReturn(Call.State.IDLE).when(mImsForegroundCall).getState();

        CallManager.setInstanceForTesting(mContext);
        mCallManagerUT = CallManager.getInstance(mContext);
        mCallManagerUT.registerPhone(mPhone);
        mCallManagerUT.registerPhone(mMockImsPhone);
    }

    @After
    public void tearDown() throws Exception {
        if (mCallManagerUT != null) {
            mCallManagerUT.unregisterPhone(mPhone);
            mCallManagerUT.unregisterPhone(mMockImsPhone);
        }
        super.tearDown();
    }

    private void triggerNewRingingConnection(Connection c) {
        ArgumentCaptor<Handler> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
        ArgumentCaptor<Integer> whatCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mPhone, atLeastOnce()).registerForNewRingingConnection(handlerCaptor.capture(),
                whatCaptor.capture(), any());

        Handler handler = handlerCaptor.getValue();
        int what = whatCaptor.getValue();

        AsyncResult ar = new AsyncResult(null, c, null);
        Message msg = Message.obtain(handler, what, ar);
        handler.sendMessage(msg);
        processAllMessages();
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_ALLOW_MULTIPLE_INCOMING_FOR_DSDA)
    public void testNewIncomingCall_DsdaEnabled_SimultaneousCapable_DoesNotHangup()
            throws Exception {
        doReturn(2).when(mTelephonyManager).getMaxNumberOfSimultaneouslyActiveSims();

        // Setup more than one ringing call
        doReturn(Call.State.WAITING).when(mRingingCall).getState();
        doReturn(Call.State.INCOMING).when(mImsRingingCall).getState();
        doReturn(mRingingCall).when(mConnection).getCall();

        triggerNewRingingConnection(mConnection);

        verify(mRingingCall, never()).hangup();
    }

    @Test
    @SmallTest
    @DisableFlags(Flags.FLAG_ALLOW_MULTIPLE_INCOMING_FOR_DSDA)
    public void testNewIncomingCall_DsdaDisabled_SimultaneousCapable_Hangups()
            throws Exception {
        doReturn(2).when(mTelephonyManager).getMaxNumberOfSimultaneouslyActiveSims();

        // Setup more than one ringing call
        doReturn(Call.State.WAITING).when(mRingingCall).getState();
        doReturn(Call.State.INCOMING).when(mImsRingingCall).getState();
        doReturn(mRingingCall).when(mConnection).getCall();

        triggerNewRingingConnection(mConnection);

        verify(mRingingCall, times(1)).hangup();
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_ALLOW_MULTIPLE_INCOMING_FOR_DSDA)
    public void testNewIncomingCall_DsdaEnabled_NotSimultaneousCapable_Hangups()
            throws Exception {
        doReturn(1).when(mTelephonyManager).getMaxNumberOfSimultaneouslyActiveSims();
        PhoneCapability capability = new PhoneCapability(1, 1,
                new ArrayList<>(), false, new int[0]);
        doReturn(capability).when(mTelephonyManager).getPhoneCapability();

        // Setup more than one ringing call
        doReturn(Call.State.WAITING).when(mRingingCall).getState();
        doReturn(Call.State.INCOMING).when(mImsRingingCall).getState();
        doReturn(mRingingCall).when(mConnection).getCall();

        triggerNewRingingConnection(mConnection);

        verify(mRingingCall, times(1)).hangup();
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_ALLOW_MULTIPLE_INCOMING_FOR_DSDA)
    public void testNewIncomingCall_DsdaEnabled_MaxActiveVoiceSubscriptions_DoesNotHangup()
            throws Exception {
        // Set max simultaneous active SIMs to 1, but max active voice subscriptions to 2
        doReturn(1).when(mTelephonyManager).getMaxNumberOfSimultaneouslyActiveSims();
        PhoneCapability capability = new PhoneCapability(2, 1,
                new ArrayList<>(), false, new int[0]);
        doReturn(capability).when(mTelephonyManager).getPhoneCapability();

        // Setup more than one ringing call
        doReturn(Call.State.WAITING).when(mRingingCall).getState();
        doReturn(Call.State.INCOMING).when(mImsRingingCall).getState();
        doReturn(mRingingCall).when(mConnection).getCall();

        triggerNewRingingConnection(mConnection);

        // Should not hangup because max active voice subscriptions > 1
        verify(mRingingCall, never()).hangup();
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_ALLOW_MULTIPLE_INCOMING_FOR_DSDA)
    public void testNewIncomingCall_DsdaEnabled_UnsupportedOperationException_Hangups()
            throws Exception {
        // Simulate TelephonyManager throwing UnsupportedOperationException
        doThrow(new UnsupportedOperationException()).when(mTelephonyManager)
                .getMaxNumberOfSimultaneouslyActiveSims();

        // Setup more than one ringing call
        doReturn(Call.State.WAITING).when(mRingingCall).getState();
        doReturn(Call.State.INCOMING).when(mImsRingingCall).getState();
        doReturn(mRingingCall).when(mConnection).getCall();

        triggerNewRingingConnection(mConnection);

        // Should hangup because exception is caught and simultaneous calling is considered disabled
        verify(mRingingCall, times(1)).hangup();
    }

    @Test
    @SmallTest
    @EnableFlags(Flags.FLAG_ALLOW_MULTIPLE_INCOMING_FOR_DSDA)
    public void testNewIncomingCall_DsdaEnabled_Exception_Hangups()
            throws Exception {
        // Simulate TelephonyManager throwing a generic Exception
        doThrow(new RuntimeException("Test Exception")).when(mTelephonyManager)
                .getMaxNumberOfSimultaneouslyActiveSims();

        // Setup more than one ringing call
        doReturn(Call.State.WAITING).when(mRingingCall).getState();
        doReturn(Call.State.INCOMING).when(mImsRingingCall).getState();
        doReturn(mRingingCall).when(mConnection).getCall();

        triggerNewRingingConnection(mConnection);

        // Should hangup because exception is caught and simultaneous calling is considered disabled
        verify(mRingingCall, times(1)).hangup();
    }
}
