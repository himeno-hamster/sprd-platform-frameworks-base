/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccCardStatus;
import com.android.internal.telephony.MccTable;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RestrictedState;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.R;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.provider.Telephony.Intents;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.TimeUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.TimeZone;
import com.android.internal.R;
/**
 * {@hide}
 */
final class GsmServiceStateTracker extends ServiceStateTracker {
    static final String LOG_TAG = "GSM";
    static final boolean DBG = true;

    GSMPhone phone;
    GsmCellLocation cellLoc;
    GsmCellLocation newCellLoc;
    int mPreferredNetworkType;

    private int gprsState = ServiceState.STATE_OUT_OF_SERVICE;
    private int newGPRSState = ServiceState.STATE_OUT_OF_SERVICE;
    private int mMaxDataCalls = 1;
    private int mNewMaxDataCalls = 1;
    private int mReasonDataDenied = -1;
    private int mNewReasonDataDenied = -1;

    /**
     * GSM roaming status solely based on TS 27.007 7.2 CREG. Only used by
     * handlePollStateResult to store CREG roaming result.
     */
    private boolean mGsmRoaming = false;

    /**
     * Data roaming status solely based on TS 27.007 10.1.19 CGREG. Only used by
     * handlePollStateResult to store CGREG roaming result.
     */
    private boolean mDataRoaming = false;

    /**
     * Mark when service state is in emergency call only mode
     */
    private boolean mEmergencyOnly = false;

    /**
     * Sometimes we get the NITZ time before we know what country we
     * are in. Keep the time zone information from the NITZ string so
     * we can fix the time zone once know the country.
     */
    private boolean mNeedFixZoneAfterNitz = false;
    private int mZoneOffset;
    private boolean mZoneDst;
    private long mZoneTime;
    private boolean mGotCountryCode = false;
    private ContentResolver cr;

    /** Boolean is true is setTimeFromNITZString was called */
    private boolean mNitzUpdatedTime = false;

    String mSavedTimeZone;
    long mSavedTime;
    long mSavedAtTime;

    /**
     * We can't register for SIM_RECORDS_LOADED immediately because the
     * SIMRecords object may not be instantiated yet.
     */
    private boolean mNeedToRegForSimLoaded;

    /** Started the recheck process after finding gprs should registered but not. */
    private boolean mStartedGprsRegCheck = false;

    /** Already sent the event-log for no gprs register. */
    private boolean mReportedGprsNoReg = false;

    /**
     * The Notification object given to the NotificationManager.
     */
    private Notification mNotification;

    /** Wake lock used while setting time of day. */
    private PowerManager.WakeLock mWakeLock;
    private static final String WAKELOCK_TAG = "ServiceStateTracker";

    /** Keep track of SPN display rules, so we only broadcast intent if something changes. */
    private String curSpn = null;
    private String curPlmn = null;
    private int curSpnRule = 0;
    private boolean curShow3G = false;

    /** waiting period before recheck gprs and voice registration. */
    static final int DEFAULT_GPRS_CHECK_PERIOD_MILLIS = 60 * 1000;

    protected static final int EVENT_ICC_STATUS_CHANGED = 39;

    /** Notification type. */
    static final int PS_ENABLED = 1001;            // Access Control blocks data service
    static final int PS_DISABLED = 1002;           // Access Control enables data service
    static final int CS_ENABLED = 1003;            // Access Control blocks all voice/sms service
    static final int CS_DISABLED = 1004;           // Access Control enables all voice/sms service
    static final int CS_NORMAL_ENABLED = 1005;     // Access Control blocks normal voice/sms service
    static final int CS_EMERGENCY_ENABLED = 1006;  // Access Control blocks emergency call service

    /** Notification id. */
    static final int PS_NOTIFICATION = 888;  // Id to update and cancel PS restricted
    static final int CS_NOTIFICATION = 999;  // Id to update and cancel CS restricted

    private boolean mLocalLanguageChange = false;// local Language change,true
                                                 // if change

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "BroadcastReceiver:phoneid="+phone.getPhoneId()+" intent=" + intent.getAction());

            if (intent.getAction().equals(Intent.ACTION_LOCALE_CHANGED)) {
                // update emergency string whenever locale changed
                mLocalLanguageChange = true;
                pollState();
                updateSpnDisplay();
            }else if(TelephonyIntents.SIM_CARD_PRESENT.equals(intent.getAction()) && intent.
                    getIntExtra("phone_id",-1)==phone.getPhoneId()){
                Log.d(LOG_TAG, " intent.getIntExtra(phone_id,-1)=" + intent.getIntExtra("phone_id",-1));
                mLocalLanguageChange = false;
                Message msg=new Message();
                msg.what=EVENT_ICC_STATUS_CHANGED;
                GsmServiceStateTracker.this.handleMessage(msg);
            }else{
                mLocalLanguageChange = false;
            }
        }
    };

    private ContentObserver mAutoTimeObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.i("GsmServiceStateTracker", "Auto time state changed");
            revertToNitzTime();
        }
    };

    private ContentObserver mAutoTimeZoneObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            Log.i("GsmServiceStateTracker", "Auto time zone state changed");
            revertToNitzTimeZone();
        }
    };

    public GsmServiceStateTracker(GSMPhone phone) {
        super();

        this.phone = phone;
        cm = phone.mCM;
        ss = new ServiceState();
        newSS = new ServiceState();
        cellLoc = new GsmCellLocation();
        newCellLoc = new GsmCellLocation();
        mSignalStrength = new SignalStrength();

        PowerManager powerManager =
                (PowerManager)phone.getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);

        cm.registerForAvailable(this, EVENT_RADIO_AVAILABLE, null);
        cm.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);

        cm.registerForVoiceNetworkStateChanged(this, EVENT_NETWORK_STATE_CHANGED, null);
        cm.setOnNITZTime(this, EVENT_NITZ_TIME, null);
        cm.setOnSignalStrengthUpdate(this, EVENT_SIGNAL_STRENGTH_UPDATE, null);
        cm.setOnRestrictedStateChanged(this, EVENT_RESTRICTED_STATE_CHANGED, null);
        phone.getIccCard().registerForReady(this, EVENT_SIM_READY, null);
        cm.setOnSimSmsReady(this, EVENT_SIM_SMS_READY, null);

        // system setting property AIRPLANE_MODE_ON is set in Settings.
        int airplaneMode = Settings.System.getInt(
                phone.getContext().getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0);
//        mDesiredPowerState = ! (airplaneMode > 0);
        boolean isStandby = Settings.System.getInt(cr,PhoneFactory.getSetting(
                Settings.System.SIM_STANDBY, phone.getPhoneId()), 1) == 1;
        boolean isNotSelected = Settings.System.getInt(cr,Settings.System.POWER_ON_STANDBY_SELECT, 0) == 0;
        mDesiredPowerState = ! (airplaneMode > 0) && isStandby && isNotSelected;

        cr = phone.getContext().getContentResolver();
        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME), true,
                mAutoTimeObserver);
        cr.registerContentObserver(
                Settings.System.getUriFor(Settings.System.AUTO_TIME_ZONE), true,
                mAutoTimeZoneObserver);

        setSignalStrengthDefaultValues();
        mNeedToRegForSimLoaded = true;

        // Monitor locale change
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        filter.addAction(TelephonyIntents.SIM_CARD_PRESENT);
        phone.getContext().registerReceiver(mIntentReceiver, filter);

        // Gsm doesn't support OTASP so its not needed
        phone.notifyOtaspChanged(OTASP_NOT_NEEDED);
    }

    public void dispose() {
        // Unregister for all events.
        cm.unregisterForAvailable(this);
        cm.unregisterForRadioStateChanged(this);
        cm.unregisterForVoiceNetworkStateChanged(this);
        phone.getIccCard().unregisterForReady(this);
        phone.mIccRecords.unregisterForRecordsLoaded(this);
        cm.unSetOnSignalStrengthUpdate(this);
        cm.unSetOnRestrictedStateChanged(this);
        cm.unSetOnNITZTime(this);
        cr.unregisterContentObserver(this.mAutoTimeObserver);
        cr.unregisterContentObserver(this.mAutoTimeZoneObserver);
        cm.unSetOnSimSmsReady(this);
    }

    protected void finalize() {
        if(DBG) log("finalize");
    }

    @Override
    protected Phone getPhone() {
        return phone;
    }

    public void handleMessage (Message msg) {
        AsyncResult ar;
        int[] ints;
        String[] strings;
        Message message;
        Intent intent;
        String action;

        if (!phone.mIsTheCurrentActivePhone) {
            Log.e(LOG_TAG, "Received message " + msg +
                    "[" + msg.what + "] while being destroyed. Ignoring.");
            return;
        }
        switch (msg.what) {
            case EVENT_RADIO_AVAILABLE:
                //this is unnecessary
                //setPowerStateToDesired();
                break;

            case EVENT_SIM_READY:
                // Set the network type, in case the radio does not restore it.
//                cm.setCurrentPreferredNetworkType();

                // The SIM is now ready i.e if it was locked
                // it has been unlocked. At this stage, the radio is already
                // powered on.
                if (mNeedToRegForSimLoaded) {
                    phone.mIccRecords.registerForRecordsLoaded(this,
                            EVENT_SIM_RECORDS_LOADED, null);
                    mNeedToRegForSimLoaded = false;
                }

                boolean skipRestoringSelection = phone.getContext().getResources().getBoolean(
                        com.android.internal.R.bool.skip_restoring_network_selection);

                if (!skipRestoringSelection) {
                    // restore the previous network selection.
                    phone.restoreSavedNetworkSelection(null);
                }
                pollState();
                // Signal strength polling stops when radio is off
                queueNextSignalStrengthPoll();
                break;

            case EVENT_ICC_STATUS_CHANGED:
            case EVENT_RADIO_STATE_CHANGED:
                // This will do nothing in the radio not
                // available case
//                setPowerStateToDesired();
                setPowerStateToDesired(false);
                pollState();
                break;

            case EVENT_NETWORK_STATE_CHANGED:
                pollState();
                break;

            case EVENT_GET_SIGNAL_STRENGTH:
                // This callback is called when signal strength is polled
                // all by itself

                if (!(cm.getRadioState().isOn())) {
                    // Polling will continue when radio turns back on
                    return;
                }
                ar = (AsyncResult) msg.obj;
                onSignalStrengthResult(ar);
                queueNextSignalStrengthPoll();

                break;

            case EVENT_GET_LOC_DONE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    String states[] = (String[])ar.result;
                    int lac = -1;
                    int cid = -1;
                    if (states.length >= 3) {
                        try {
                            if (states[1] != null && states[1].length() > 0) {
                                lac = Integer.parseInt(states[1], 16);
                            }
                            if (states[2] != null && states[2].length() > 0) {
                                cid = Integer.parseInt(states[2], 16);
                            }
                        } catch (NumberFormatException ex) {
                            Log.w(LOG_TAG, "error parsing location: " + ex);
                        }
                    }
                    cellLoc.setLacAndCid(lac, cid);
                    phone.notifyLocationChanged();
                }

                // Release any temporary cell lock, which could have been
                // acquired to allow a single-shot location update.
                disableSingleLocationUpdate();
                break;

            case EVENT_POLL_STATE_REGISTRATION:
            case EVENT_POLL_STATE_GPRS:
            case EVENT_POLL_STATE_OPERATOR:
            case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
                ar = (AsyncResult) msg.obj;

                handlePollStateResult(msg.what, ar);
                break;

            case EVENT_POLL_SIGNAL_STRENGTH:
                // Just poll signal strength...not part of pollState()

                cm.getSignalStrength(obtainMessage(EVENT_GET_SIGNAL_STRENGTH));
                break;

            case EVENT_NITZ_TIME:
                ar = (AsyncResult) msg.obj;

                String nitzString = (String)((Object[])ar.result)[0];
                long nitzReceiveTime = ((Long)((Object[])ar.result)[1]).longValue();

                setTimeFromNITZString(nitzString, nitzReceiveTime);
                break;

            case EVENT_SIGNAL_STRENGTH_UPDATE:
                // This is a notification from
                // CommandsInterface.setOnSignalStrengthUpdate

                ar = (AsyncResult) msg.obj;

                // The radio is telling us about signal strength changes
                // we don't have to ask it
                dontPollSignalStrength = true;

                onSignalStrengthResult(ar);
                break;

            case EVENT_SIM_RECORDS_LOADED:
                updateSpnDisplay();
                break;

            case EVENT_LOCATION_UPDATES_ENABLED:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    cm.getVoiceRegistrationState(obtainMessage(EVENT_GET_LOC_DONE, null));
                }
                break;

            case EVENT_SET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;
                // Don't care the result, only use for dereg network (COPS=2)
                message = obtainMessage(EVENT_RESET_PREFERRED_NETWORK_TYPE, ar.userObj);
                cm.setPreferredNetworkType(mPreferredNetworkType, message);
                break;

            case EVENT_RESET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;
                if (ar.userObj != null) {
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;

            case EVENT_GET_PREFERRED_NETWORK_TYPE:
                ar = (AsyncResult) msg.obj;

                if (ar.exception == null) {
                    mPreferredNetworkType = ((int[])ar.result)[0];
                } else {
                    mPreferredNetworkType = RILConstants.NETWORK_MODE_GLOBAL;
                }

                message = obtainMessage(EVENT_SET_PREFERRED_NETWORK_TYPE, ar.userObj);
                int toggledNetworkType = RILConstants.NETWORK_MODE_GLOBAL;

                cm.setPreferredNetworkType(toggledNetworkType, message);
                break;

            case EVENT_CHECK_REPORT_GPRS:
                if (ss != null && !isGprsConsistent(gprsState, ss.getState())) {

                    // Can't register data service while voice service is ok
                    // i.e. CREG is ok while CGREG is not
                    // possible a network or baseband side error
                    GsmCellLocation loc = ((GsmCellLocation)phone.getCellLocation());
                    EventLog.writeEvent(EventLogTags.DATA_NETWORK_REGISTRATION_FAIL,
                            ss.getOperatorNumeric(), loc != null ? loc.getCid() : -1);
                    mReportedGprsNoReg = true;
                }
                mStartedGprsRegCheck = false;
                break;

            case EVENT_RESTRICTED_STATE_CHANGED:
                // This is a notification from
                // CommandsInterface.setOnRestrictedStateChanged

                if (DBG) log("EVENT_RESTRICTED_STATE_CHANGED");

                ar = (AsyncResult) msg.obj;

                onRestrictedStateChanged(ar);
                break;

            case EVENT_SIM_SMS_READY:
                intent = new Intent(PhoneFactory.getAction(TelephonyIntents.ACTION_IS_SIM_SMS_READY, phone.getPhoneId()));
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra("isReady", true);
                intent.putExtra("phoneId", phone.getPhoneId());
                log("send broadcast for EVENT_SIM_SMS_READY phoneId=" + phone.getPhoneId());
                phone.getContext().sendStickyBroadcast(intent);
                break;

            default:
                super.handleMessage(msg);
            break;
        }
    }


    protected void setPowerStateToDesired() {
        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
            && cm.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            cm.setRadioPower(true, null);
        } else if (!mDesiredPowerState && cm.getRadioState().isOn()) {
            // If it's on and available and we want it off gracefully
            DataConnectionTracker dcTracker = phone.mDataConnectionTracker;
            powerOffRadioSafely(dcTracker);
        } // Otherwise, we're in the desired state
    }


    protected void setPowerStateToDesired(boolean force) {
        // If we want it on and it's off, turn it on
        if (mDesiredPowerState
            && cm.getRadioState() == CommandsInterface.RadioState.RADIO_OFF) {
            // don't set radio_power on when no card
            if (force || phone.getIccCard().getIccCardState() != IccCard.State.ABSENT
                    && phone.getIccCard().getIccCardState() != IccCard.State.UNKNOWN) {
                cm.setRadioPower(true, null);
                log("setPowerStateToDesired : setRadioPower  force --"+force);
            }
        } else if (!mDesiredPowerState && cm.getRadioState().isOn()) {
            // If it's on and available and we want it off gracefully
            DataConnectionTracker dcTracker = phone.mDataConnectionTracker;
            powerOffRadioSafely(dcTracker);
        } // Otherwise, we're in the desired state
    }

    @Override
    protected void hangupAndPowerOff() {
        // hang up all active voice calls
        if (phone.isInCall()) {
            phone.mCT.ringingCall.hangupIfAlive();
            phone.mCT.backgroundCall.hangupIfAlive();
            phone.mCT.foregroundCall.hangupIfAlive();
        }

        cm.setRadioPower(false, null);
    }
    //change SPN to chinese
    private String updateSpnToChinese(String numeric, String name) {
        Resources r = Resources.getSystem();
        String newName;
        Log.d(LOG_TAG, "getCarrierNumericToChinese: old name= " + name+" numeric="+numeric);
        if ("46000".equals(numeric) ||
               "46002".equals(numeric) ||
               "46007".equals(numeric)) {
            newName = r.getString(R.string.china_mobile_numeric_values);
            if (newName != null && newName.equals("CMCC")) {
                return name;
            } else {
                return newName;
            }
        }
        if ("46001".equals(numeric)) {
            newName = r.getString(R.string.china_unicom_numeric_values);
            if (newName != null && newName.equals("CUCC")) {
                return name;
            } else {
                return newName;
            }
        }
        return name;
    }

    protected void updateSpnDisplay() {
        int rule = phone.mIccRecords.getDisplayRule(ss.getOperatorNumeric());
        String spn = phone.mIccRecords.getServiceProviderName();
        String plmn = ss.getOperatorAlphaLong();
        boolean show3G = false;

        if(plmn == null || plmn.length() == 0) {
          plmn = ss.getOperatorAlphaShort();
        }
        // For white-card test,both short and long name are null but Operator name is't null,
        //so display Operator name,bug 8970
        if(plmn == null || plmn.length() == 0) {
            plmn = ss.getOperatorNumeric();
        }

        if (mRilRadioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA ||
            mRilRadioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSPA  ||
            mRilRadioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP ||
            mRilRadioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA ||
            mRilRadioTechnology == ServiceState.RIL_RADIO_TECHNOLOGY_UMTS) {
            show3G = true;
        } else {
            show3G = false;
        }

        if(plmn == null || plmn.length() == 0) {
            plmn = ss.getOperatorAlphaShort();
        }
        // For white-card test,both short and long name are null but Operator name is't null,
        //so display Operator name,bug 8970
        if(plmn == null || plmn.length() == 0) {
            plmn = ss.getOperatorNumeric();
        }
        int phoneId = phone.getPhoneId();

        // For emergency calls only, pass the EmergencyCallsOnly string via EXTRA_PLMN
        if (mEmergencyOnly && cm.getRadioState().isOn()) {
            plmn = Resources.getSystem().
                getText(com.android.internal.R.string.emergency_calls_only).toString();
            show3G = false;
            if (DBG) log("updateSpnDisplay: emergency only and radio is on plmn='" + plmn + "'");
        }

      if(plmn == null || plmn.length() == 0){
          plmn = Resources.getSystem().
              getText(com.android.internal.R.string.lockscreen_carrier_default).toString();
          show3G = false;
        }
        if (rule != curSpnRule || mLocalLanguageChange
                || !TextUtils.equals(spn, curSpn)
                || !TextUtils.equals(plmn, curPlmn)) {
            boolean showSpn = !mEmergencyOnly && !TextUtils.isEmpty(spn)
                && (rule & SIMRecords.SPN_RULE_SHOW_SPN) == SIMRecords.SPN_RULE_SHOW_SPN;
            boolean showPlmn = !TextUtils.isEmpty(plmn) && (mEmergencyOnly ||
                ((rule & SIMRecords.SPN_RULE_SHOW_PLMN) == SIMRecords.SPN_RULE_SHOW_PLMN));
            if (showPlmn) {
               if (show3G) {
                    if (plmn != null && plmn.length()!=0) {
                        plmn += "3G";
                    }
                }
            } else {
                if (showSpn) {
                    if (spn != null && spn.length() != 0) {
                        String operNum = phone.mIccRecords.getOperatorNumeric();
                        if (operNum != null &&
                            (operNum.equals("46000") ||
                             operNum.equals("46001") ||
                             operNum.equals("46002") ||
                             operNum.equals("46007"))) {
                            spn = updateSpnToChinese(ss.getOperatorNumeric(), spn);
                        }
                    }
                    if (show3G) {
                        if (spn != null && spn.length()!=0) {
                            spn += "3G";
                        }
                    }
                }
            }
            if (DBG) {
                log(String.format("updateSpnDisplay: changed sending intent" + " rule=" + rule +
                            " showPlmn='%b' plmn='%s' showSpn='%b' spn='%s'",
                            showPlmn, plmn, showSpn, spn));
            }
            Intent intent = new Intent(Intents.SPN_STRINGS_UPDATED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(Intents.EXTRA_SHOW_SPN, showSpn);
            intent.putExtra(Intents.EXTRA_SPN, spn);
            intent.putExtra(Intents.EXTRA_SHOW_PLMN, showPlmn);
            intent.putExtra(Intents.EXTRA_PLMN, plmn);
            intent.putExtra(Intents.EXTRA_PHONE_ID, phoneId);
            phone.getContext().sendStickyBroadcast(intent);
        }

        curSpnRule = rule;
        curSpn = spn;
        curPlmn = plmn;
        curShow3G = show3G;
    }

    /**
     * Handle the result of one of the pollState()-related requests
     */
    protected void handlePollStateResult (int what, AsyncResult ar) {
        int ints[];
        String states[];
        int type = 0;

        // Ignore stale requests from last poll
        if (ar.userObj != pollingContext) return;

        if (ar.exception != null) {
            CommandException.Error err=null;

            if (ar.exception instanceof CommandException) {
                err = ((CommandException)(ar.exception)).getCommandError();
            }

            if (err == CommandException.Error.RADIO_NOT_AVAILABLE) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (!cm.getRadioState().isOn()) {
                // Radio has crashed or turned off
                cancelPollState();
                return;
            }

            if (err != CommandException.Error.OP_NOT_ALLOWED_BEFORE_REG_NW) {
                loge("RIL implementation has returned an error where it must succeed" +
                        ar.exception);
            }
        } else try {
            switch (what) {
                case EVENT_POLL_STATE_REGISTRATION:
                    states = (String[])ar.result;
                    int lac = -1;
                    int cid = -1;
                    int regState = -1;
                    int reasonRegStateDenied = -1;
                    int psc = -1;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);
                            if (states.length >= 3) {
                                if (states[1] != null && states[1].length() > 0) {
                                    lac = Integer.parseInt(states[1], 16);
                                }
                                if (states[2] != null && states[2].length() > 0) {
                                    cid = Integer.parseInt(states[2], 16);
                                }
                            }
                            if (states.length >= 4 && states[3] != null) {
                                type = Integer.parseInt(states[3]);
                            }
                            if (states.length > 14) {
                                if (states[14] != null && states[14].length() > 0) {
                                    psc = Integer.parseInt(states[14], 16);
                                }
                            }
                        } catch (NumberFormatException ex) {
                            loge("error parsing RegistrationState: " + ex);
                        }
                    }

                    mGsmRoaming = regCodeIsRoaming(regState);
                    newSS.setState (regCodeToServiceState(regState));

                    // if (regState == 10 || regState == 12 || regState == 13 || regState == 14) {
                    if (regState == 3 || regState == 6 || regState == 10 || regState == 12 || regState == 13 || regState == 14) {
                        mEmergencyOnly = true;
                    } else {
                        mEmergencyOnly = false;
                    }
		    log("Set mEmergencyOnly = " + mEmergencyOnly);

                    // LAC and CID are -1 if not avail
                    newCellLoc.setLacAndCid(lac, cid);
                    newCellLoc.setPsc(psc);
                break;

                case EVENT_POLL_STATE_GPRS:
                    states = (String[])ar.result;

                    regState = -1;
                    mNewReasonDataDenied = -1;
                    mNewMaxDataCalls = 1;
                    if (states.length > 0) {
                        try {
                            regState = Integer.parseInt(states[0]);

                            // states[3] (if present) is the current radio technology
                            if (states.length >= 4 && states[3] != null) {
                                type = Integer.parseInt(states[3]);
                            }
                            if ((states.length >= 5 ) && (regState == 3)) {
                                mNewReasonDataDenied = Integer.parseInt(states[4]);
                            }
                            if (states.length >= 6) {
                                mNewMaxDataCalls = Integer.parseInt(states[5]);
                            }
                        } catch (NumberFormatException ex) {
                            loge("error parsing GprsRegistrationState: " + ex);
                        }
                    }
                    newGPRSState = regCodeToServiceState(regState);
                    mDataRoaming = regCodeIsRoaming(regState);
                    mNewRilRadioTechnology = type;
                    newSS.setRadioTechnology(type);
                break;

                case EVENT_POLL_STATE_OPERATOR:
                    String opNames[] = (String[])ar.result;

                    if (opNames != null && opNames.length >= 3) {
                         newSS.setOperatorName (opNames[0], opNames[1], opNames[2]);
                    }
                break;

                case EVENT_POLL_STATE_NETWORK_SELECTION_MODE:
                    ints = (int[])ar.result;
                    newSS.setIsManualSelection(ints[0] == 1);
                break;
            }

        } catch (RuntimeException ex) {
            loge("Exception while polling service state. Probably malformed RIL response." + ex);
        }

        pollingContext[0]--;

        if (pollingContext[0] == 0) {
            /**
             *  Since the roaming states of gsm service (from +CREG) and
             *  data service (from +CGREG) could be different, the new SS
             *  is set roaming while either one is roaming.
             *
             *  There is an exception for the above rule. The new SS is not set
             *  as roaming while gsm service reports roaming but indeed it is
             *  not roaming between operators.
             */
            boolean roaming = (mGsmRoaming || mDataRoaming);
            if (mGsmRoaming && !isRoamingBetweenOperators(mGsmRoaming, newSS)) {
                roaming = false;
            }
            newSS.setRoaming(roaming);
            newSS.setEmergencyOnly(mEmergencyOnly);
            pollStateDone();
        }
    }

    private void setSignalStrengthDefaultValues() {
        // TODO Make a constructor only has boolean gsm as parameter
        mSignalStrength = new SignalStrength(99, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, SignalStrength.INVALID_SNR, -1, true);
    }

    /**
     * A complete "service state" from our perspective is
     * composed of a handful of separate requests to the radio.
     *
     * We make all of these requests at once, but then abandon them
     * and start over again if the radio notifies us that some
     * event has changed
     */
    private void pollState() {
        pollingContext = new int[1];
        pollingContext[0] = 0;

        switch (cm.getRadioState()) {
            case RADIO_UNAVAILABLE:
                newSS.setStateOutOfService();
                newCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
                pollStateDone();
            break;

            case RADIO_OFF:
                newSS.setStateOff();
                newCellLoc.setStateInvalid();
                setSignalStrengthDefaultValues();
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
                pollStateDone();
            break;

            default:
                // Issue all poll-related commands at once
                // then count down the responses, which
                // are allowed to arrive out-of-order

                pollingContext[0]++;
                cm.getOperator(
                    obtainMessage(
                        EVENT_POLL_STATE_OPERATOR, pollingContext));

                pollingContext[0]++;
                cm.getDataRegistrationState(
                    obtainMessage(
                        EVENT_POLL_STATE_GPRS, pollingContext));

                pollingContext[0]++;
                cm.getVoiceRegistrationState(
                    obtainMessage(
                        EVENT_POLL_STATE_REGISTRATION, pollingContext));

                pollingContext[0]++;
                cm.getNetworkSelectionMode(
                    obtainMessage(
                        EVENT_POLL_STATE_NETWORK_SELECTION_MODE, pollingContext));
            break;
        }
    }

    private void pollStateDone() {
        if (DBG) {
            log("[" + phone.getPhoneId() + "]Poll ServiceState done: " +
                " oldSS=[" + ss + "] newSS=[" + newSS +
                "] oldGprs=" + gprsState + " newData=" + newGPRSState +
                " oldMaxDataCalls=" + mMaxDataCalls +
                " mNewMaxDataCalls=" + mNewMaxDataCalls +
                " oldReasonDataDenied=" + mReasonDataDenied +
                " mNewReasonDataDenied=" + mNewReasonDataDenied +
                " oldType=" + ServiceState.rilRadioTechnologyToString(mRilRadioTechnology) +
                " newType=" + ServiceState.rilRadioTechnologyToString(mNewRilRadioTechnology));
        }

        boolean hasRegistered =
            ss.getState() != ServiceState.STATE_IN_SERVICE
            && newSS.getState() == ServiceState.STATE_IN_SERVICE;

        boolean hasDeregistered =
            ss.getState() == ServiceState.STATE_IN_SERVICE
            && newSS.getState() != ServiceState.STATE_IN_SERVICE;

        boolean hasGprsAttached =
                gprsState != ServiceState.STATE_IN_SERVICE
                && newGPRSState == ServiceState.STATE_IN_SERVICE;

        boolean hasGprsDetached =
                gprsState == ServiceState.STATE_IN_SERVICE
                && newGPRSState != ServiceState.STATE_IN_SERVICE;

        boolean hasRadioTechnologyChanged = mRilRadioTechnology != mNewRilRadioTechnology;

        boolean hasChanged = !newSS.equals(ss);

        boolean hasRoamingOn = !ss.getRoaming() && newSS.getRoaming();

        boolean hasRoamingOff = ss.getRoaming() && !newSS.getRoaming();

        boolean hasLocationChanged = !newCellLoc.equals(cellLoc);

        // Add an event log when connection state changes
        if (ss.getState() != newSS.getState() || gprsState != newGPRSState) {
            EventLog.writeEvent(EventLogTags.GSM_SERVICE_STATE_CHANGE,
                ss.getState(), gprsState, newSS.getState(), newGPRSState);
        }

        ServiceState tss;
        tss = ss;
        ss = newSS;
        newSS = tss;
        // clean slate for next time
        newSS.setStateOutOfService();

        GsmCellLocation tcl = cellLoc;
        cellLoc = newCellLoc;
        newCellLoc = tcl;

        // Add an event log when network type switched
        // TODO: we may add filtering to reduce the event logged,
        // i.e. check preferred network setting, only switch to 2G, etc
        if (hasRadioTechnologyChanged) {
            int cid = -1;
            GsmCellLocation loc = ((GsmCellLocation)phone.getCellLocation());
            if (loc != null) cid = loc.getCid();
            EventLog.writeEvent(EventLogTags.GSM_RAT_SWITCHED, cid, mRilRadioTechnology,
                    mNewRilRadioTechnology);
            if (DBG) {
                log("RAT switched " + ServiceState.rilRadioTechnologyToString(mRilRadioTechnology) +
                        " -> " + ServiceState.rilRadioTechnologyToString(mNewRilRadioTechnology) +
                        " at cell " + cid);
            }
        }

        gprsState = newGPRSState;
        mReasonDataDenied = mNewReasonDataDenied;
        mMaxDataCalls = mNewMaxDataCalls;
        mRilRadioTechnology = mNewRilRadioTechnology;
        // this new state has been applied - forget it until we get a new new state
        mNewRilRadioTechnology = 0;


        newSS.setStateOutOfService(); // clean slate for next time

        if (hasRadioTechnologyChanged) {
            phone.setSystemProperty(TelephonyProperties.PROPERTY_DATA_NETWORK_TYPE,
                    ServiceState.rilRadioTechnologyToString(mRilRadioTechnology));
        }

        if (hasRegistered) {
            mNetworkAttachedRegistrants.notifyRegistrants();

            if (DBG) {
                log("pollStateDone: registering current mNitzUpdatedTime=" +
                        mNitzUpdatedTime + " changing to false");
            }
            mNitzUpdatedTime = false;
        }

        if (hasChanged) {
            String operatorNumeric;

            updateSpnDisplay();

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ALPHA,
                ss.getOperatorAlphaLong());

            String prevOperatorNumeric =
                    SystemProperties.get(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, "");
            operatorNumeric = ss.getOperatorNumeric();
            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_NUMERIC, operatorNumeric);

            if (operatorNumeric == null) {
                if (DBG) log("operatorNumeric is null");
                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, "");
                mGotCountryCode = false;
                mNitzUpdatedTime = false;
            } else {
                String iso = "";
                String mcc = operatorNumeric.substring(0, 3);
                try{
                    iso = MccTable.countryCodeForMcc(Integer.parseInt(mcc));
                } catch ( NumberFormatException ex){
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                } catch ( StringIndexOutOfBoundsException ex) {
                    loge("pollStateDone: countryCodeForMcc error" + ex);
                }

                phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, iso);
                mGotCountryCode = true;

                TimeZone zone = null;

                if (!mNitzUpdatedTime && !mcc.equals("000") && !TextUtils.isEmpty(iso) &&
                        getAutoTimeZone()) {

                    // Test both paths if ignore nitz is true
                    boolean testOneUniqueOffsetPath = SystemProperties.getBoolean(
                                TelephonyProperties.PROPERTY_IGNORE_NITZ, false) &&
                                    ((SystemClock.uptimeMillis() & 1) == 0);

                    ArrayList<TimeZone> uniqueZones = TimeUtils.getTimeZonesWithUniqueOffsets(iso);
                    if ((uniqueZones.size() == 1) || testOneUniqueOffsetPath) {
                        zone = uniqueZones.get(0);
                        if (DBG) {
                           log("pollStateDone: no nitz but one TZ for iso-cc=" + iso +
                                   " with zone.getID=" + zone.getID() +
                                   " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath);
                        }
                        setAndBroadcastNetworkSetTimeZone(zone.getID());
                    } else {
                        if (DBG) {
                            log("pollStateDone: there are " + uniqueZones.size() +
                                " unique offsets for iso-cc='" + iso +
                                " testOneUniqueOffsetPath=" + testOneUniqueOffsetPath +
                                "', do nothing");
                        }
                    }
                }

                if (shouldFixTimeZoneNow(phone, operatorNumeric, prevOperatorNumeric,
                        mNeedFixZoneAfterNitz)) {
                    // If the offset is (0, false) and the timezone property
                    // is set, use the timezone property rather than
                    // GMT.
                    String zoneName = SystemProperties.get(TIMEZONE_PROPERTY);
                    if (DBG) {
                        log("pollStateDone: fix time zone zoneName='" + zoneName +
                            "' mZoneOffset=" + mZoneOffset + " mZoneDst=" + mZoneDst +
                            " iso-cc='" + iso +
                            "' iso-cc-idx=" + Arrays.binarySearch(GMT_COUNTRY_CODES, iso));
                    }

                    // "(mZoneOffset == 0) && (mZoneDst == false) &&
                    //  (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)"
                    // means that we received a NITZ string telling
                    // it is in GMT+0 w/ DST time zone
                    // BUT iso tells is NOT, e.g, a wrong NITZ reporting
                    // local time w/ 0 offset.
                    if ((mZoneOffset == 0) && (mZoneDst == false) &&
                        (zoneName != null) && (zoneName.length() > 0) &&
                        (Arrays.binarySearch(GMT_COUNTRY_CODES, iso) < 0)) {
                        zone = TimeZone.getDefault();
                        if (mNeedFixZoneAfterNitz) {
                            // For wrong NITZ reporting local time w/ 0 offset,
                            // need adjust time to reflect default timezone setting
                            long ctm = System.currentTimeMillis();
                            long tzOffset = zone.getOffset(ctm);
                            if (DBG) {
                                log("pollStateDone: tzOffset=" + tzOffset + " ltod=" +
                                        TimeUtils.logTimeOfDay(ctm));
                            }
                            if (getAutoTime()) {
                                long adj = ctm - tzOffset;
                                if (DBG) log("pollStateDone: adj ltod=" +
                                        TimeUtils.logTimeOfDay(adj));
                                setAndBroadcastNetworkSetTime(adj);
                            } else {
                                // Adjust the saved NITZ time to account for tzOffset.
                                mSavedTime = mSavedTime - tzOffset;
                            }
                        }
                        if (DBG) log("pollStateDone: using default TimeZone");
                    } else if (iso.equals("")){
                        // Country code not found.  This is likely a test network.
                        // Get a TimeZone based only on the NITZ parameters (best guess).
                        zone = getNitzTimeZone(mZoneOffset, mZoneDst, mZoneTime);
                        if (DBG) log("pollStateDone: using NITZ TimeZone");
                    } else {
                        zone = TimeUtils.getTimeZone(mZoneOffset, mZoneDst, mZoneTime, iso);
                        if (DBG) log("pollStateDone: using getTimeZone(off, dst, time, iso)");
                    }

                    mNeedFixZoneAfterNitz = false;

                    if (zone != null) {
                        log("pollStateDone: zone != null zone.getID=" + zone.getID());
                        if (getAutoTimeZone()) {
                            setAndBroadcastNetworkSetTimeZone(zone.getID());
                        }
                        saveNitzTimeZone(zone.getID());
                    } else {
                        log("pollStateDone: zone == null");
                    }
                }
            }

            phone.setSystemProperty(TelephonyProperties.PROPERTY_OPERATOR_ISROAMING,
                ss.getRoaming() ? "true" : "false");

            phone.notifyServiceStateChanged(ss);
        }

        if (hasGprsAttached) {
            mAttachedRegistrants.notifyRegistrants();
        }

        if (hasGprsDetached) {
            mDetachedRegistrants.notifyRegistrants();
        }

        if (hasRadioTechnologyChanged) {
            phone.notifyDataConnection(Phone.REASON_NW_TYPE_CHANGED);
        }

        if (hasRoamingOn) {
            mRoamingOnRegistrants.notifyRegistrants();
        }

        if (hasRoamingOff) {
            mRoamingOffRegistrants.notifyRegistrants();
        }

        if (hasLocationChanged) {
            phone.notifyLocationChanged();
        }

        if (! isGprsConsistent(gprsState, ss.getState())) {
            if (!mStartedGprsRegCheck && !mReportedGprsNoReg) {
                mStartedGprsRegCheck = true;

                int check_period = Settings.Secure.getInt(
                        phone.getContext().getContentResolver(),
                        Settings.Secure.GPRS_REGISTER_CHECK_PERIOD_MS,
                        DEFAULT_GPRS_CHECK_PERIOD_MILLIS);
                sendMessageDelayed(obtainMessage(EVENT_CHECK_REPORT_GPRS),
                        check_period);
            }
        } else {
            mReportedGprsNoReg = false;
        }
    }

    /**
     * Check if GPRS got registered while voice is registered.
     *
     * @param gprsState for GPRS registration state, i.e. CGREG in GSM
     * @param serviceState for voice registration state, i.e. CREG in GSM
     * @return false if device only register to voice but not gprs
     */
    private boolean isGprsConsistent(int gprsState, int serviceState) {
        return !((serviceState == ServiceState.STATE_IN_SERVICE) &&
                (gprsState != ServiceState.STATE_IN_SERVICE));
    }

    /**
     * Returns a TimeZone object based only on parameters from the NITZ string.
     */
    private TimeZone getNitzTimeZone(int offset, boolean dst, long when) {
        TimeZone guess = findTimeZone(offset, dst, when);
        if (guess == null) {
            // Couldn't find a proper timezone.  Perhaps the DST data is wrong.
            guess = findTimeZone(offset, !dst, when);
        }
        if (DBG) log("getNitzTimeZone returning " + (guess == null ? guess : guess.getID()));
        return guess;
    }

    private TimeZone findTimeZone(int offset, boolean dst, long when) {
        int rawOffset = offset;
        if (dst) {
            rawOffset -= 3600000;
        }
        String[] zones = TimeZone.getAvailableIDs(rawOffset);
        TimeZone guess = null;
        Date d = new Date(when);
        for (String zone : zones) {
            TimeZone tz = TimeZone.getTimeZone(zone);
            if (tz.getOffset(when) == offset &&
                tz.inDaylightTime(d) == dst) {
                guess = tz;
                break;
            }
        }

        return guess;
    }

    private void queueNextSignalStrengthPoll() {
        if (dontPollSignalStrength) {
            // The radio is telling us about signal strength changes
            // we don't have to ask it
            return;
        }

        Message msg;

        msg = obtainMessage();
        msg.what = EVENT_POLL_SIGNAL_STRENGTH;

        long nextTime;

        // TODO Don't poll signal strength if screen is off
        sendMessageDelayed(msg, POLL_PERIOD_MILLIS);
    }

    /**
     *  Send signal-strength-changed notification if changed.
     *  Called both for solicited and unsolicited signal strength updates.
     */
    private void onSignalStrengthResult(AsyncResult ar) {
        SignalStrength oldSignalStrength = mSignalStrength;
        int rssi = 99;
        int lteSignalStrength = -1;
        int lteRsrp = -1;
        int lteRsrq = -1;
        int lteRssnr = SignalStrength.INVALID_SNR;
        int lteCqi = -1;

        if (ar.exception != null) {
            // -1 = unknown
            // most likely radio is resetting/disconnected
            setSignalStrengthDefaultValues();
        } else {
            int[] ints = (int[])ar.result;

            // bug 658816 seems to be a case where the result is 0-length
            if (ints.length != 0) {
                rssi = ints[0];
                lteSignalStrength = ints[7];
                lteRsrp = ints[8];
                lteRsrq = ints[9];
                lteRssnr = ints[10];
                lteCqi = ints[11];
            } else {
                loge("Bogus signal strength response");
                rssi = 99;
            }
        }

        mSignalStrength = new SignalStrength(rssi, -1, -1, -1,
                -1, -1, -1, lteSignalStrength, lteRsrp, lteRsrq, lteRssnr, lteCqi, true);

        if (!mSignalStrength.equals(oldSignalStrength)) {
            try { // This takes care of delayed EVENT_POLL_SIGNAL_STRENGTH (scheduled after
                  // POLL_PERIOD_MILLIS) during Radio Technology Change)
                phone.notifySignalStrength();
           } catch (NullPointerException ex) {
                log("onSignalStrengthResult() Phone already destroyed: " + ex
                        + "SignalStrength not notified");
           }
        }
    }

    /**
     * Set restricted state based on the OnRestrictedStateChanged notification
     * If any voice or packet restricted state changes, trigger a UI
     * notification and notify registrants when sim is ready.
     *
     * @param ar an int value of RIL_RESTRICTED_STATE_*
     */
    private void onRestrictedStateChanged(AsyncResult ar) {
        RestrictedState newRs = new RestrictedState();

        if (DBG) log("onRestrictedStateChanged: E rs "+ mRestrictedState);

        if (ar.exception == null) {
            int[] ints = (int[])ar.result;
            int state = ints[0];

            newRs.setCsEmergencyRestricted(
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_EMERGENCY) != 0) ||
                    ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
            //ignore the normal call and data restricted state before SIM READY
            if (phone.getIccCard().getState() == IccCard.State.READY) {
                newRs.setCsNormalRestricted(
                        ((state & RILConstants.RIL_RESTRICTED_STATE_CS_NORMAL) != 0) ||
                        ((state & RILConstants.RIL_RESTRICTED_STATE_CS_ALL) != 0) );
                newRs.setPsRestricted(
                        (state & RILConstants.RIL_RESTRICTED_STATE_PS_ALL)!= 0);
            }

            if (DBG) log("onRestrictedStateChanged: new rs "+ newRs);

            if (!mRestrictedState.isPsRestricted() && newRs.isPsRestricted()) {
                mPsRestrictEnabledRegistrants.notifyRegistrants();
                setNotification(PS_ENABLED);
            } else if (mRestrictedState.isPsRestricted() && !newRs.isPsRestricted()) {
                mPsRestrictDisabledRegistrants.notifyRegistrants();
                setNotification(PS_DISABLED);
            }

            /**
             * There are two kind of cs restriction, normal and emergency. So
             * there are 4 x 4 combinations in current and new restricted states
             * and we only need to notify when state is changed.
             */
            if (mRestrictedState.isCsRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (!newRs.isCsNormalRestricted()) {
                    // remove normal restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (!newRs.isCsEmergencyRestricted()) {
                    // remove emergency restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (mRestrictedState.isCsEmergencyRestricted() &&
                    !mRestrictedState.isCsNormalRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // remove emergency restriction and enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            } else if (!mRestrictedState.isCsEmergencyRestricted() &&
                    mRestrictedState.isCsNormalRestricted()) {
                if (!newRs.isCsRestricted()) {
                    // remove all restriction
                    setNotification(CS_DISABLED);
                } else if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // remove normal restriction and enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                }
            } else {
                if (newRs.isCsRestricted()) {
                    // enable all restriction
                    setNotification(CS_ENABLED);
                } else if (newRs.isCsEmergencyRestricted()) {
                    // enable emergency restriction
                    setNotification(CS_EMERGENCY_ENABLED);
                } else if (newRs.isCsNormalRestricted()) {
                    // enable normal restriction
                    setNotification(CS_NORMAL_ENABLED);
                }
            }

            mRestrictedState = newRs;
        }
        log("onRestrictedStateChanged: X rs "+ mRestrictedState);
    }

    /** code is registration state 0-5 from TS 27.007 7.2 */
    private int regCodeToServiceState(int code) {
        switch (code) {
            case 0:
            case 2: // 2 is "searching"
            case 3: // 3 is "registration denied"
            case 4: // 4 is "unknown" no vaild in current baseband
	    case 8: // 8 define emergency only
            case 10:// same as 0, but indicates that emergency call is possible.
            case 12:// same as 2, but indicates that emergency call is possible.
            case 13:// same as 3, but indicates that emergency call is possible.
            case 14:// same as 4, but indicates that emergency call is possible.
                return ServiceState.STATE_OUT_OF_SERVICE;

            case 1:
                return ServiceState.STATE_IN_SERVICE;

            case 5:
                // in service, roam
                return ServiceState.STATE_IN_SERVICE;

            default:
                loge("regCodeToServiceState: unexpected service state " + code);
                return ServiceState.STATE_OUT_OF_SERVICE;
        }
    }


    /**
     * code is registration state 0-5 from TS 27.007 7.2
     * returns true if registered roam, false otherwise
     */
    private boolean regCodeIsRoaming (int code) {
        // 5 is  "in service -- roam"
        return 5 == code;
    }

    /**
     * Set roaming state when gsmRoaming is true and, if operator mcc is the
     * same as sim mcc, ons is different from spn
     * @param gsmRoaming TS 27.007 7.2 CREG registered roaming
     * @param s ServiceState hold current ons
     * @return true for roaming state set
     */
    private boolean isRoamingBetweenOperators(boolean gsmRoaming, ServiceState s) {
        String iccOperatorAlphaProperty = PhoneFactory.getProperty(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA, phone.getPhoneId());
        String spn = SystemProperties.get(iccOperatorAlphaProperty, "empty");

        String onsl = s.getOperatorAlphaLong();
        String onss = s.getOperatorAlphaShort();

        boolean equalsOnsl = onsl != null && spn.equals(onsl);
        boolean equalsOnss = onss != null && spn.equals(onss);

        String iccOperatorNumericProperty = PhoneFactory.getProperty(
                TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC, phone.getPhoneId());
        String simNumeric = SystemProperties.get(
                iccOperatorNumericProperty, "");
        String  operatorNumeric = s.getOperatorNumeric();

        boolean equalsMcc = true;
        try {
            equalsMcc = simNumeric.substring(0, 3).
                    equals(operatorNumeric.substring(0, 3));
        } catch (Exception e){
        }

        return gsmRoaming && !(equalsMcc && (equalsOnsl || equalsOnss));
    }

    private static int twoDigitsAt(String s, int offset) {
        int a, b;

        a = Character.digit(s.charAt(offset), 10);
        b = Character.digit(s.charAt(offset+1), 10);

        if (a < 0 || b < 0) {

            throw new RuntimeException("invalid format");
        }

        return a*10 + b;
    }

    /**
     * @return The current GPRS state. IN_SERVICE is the same as "attached"
     * and OUT_OF_SERVICE is the same as detached.
     */
    int getCurrentGprsState() {
        return gprsState;
    }

    public int getCurrentDataConnectionState() {
        return gprsState;
    }

    /**
     * @return true if phone is camping on a technology (eg UMTS)
     * that could support voice and data simultaneously.
     */
    public boolean isConcurrentVoiceAndDataAllowed() {
        return (mRilRadioTechnology >= ServiceState.RIL_RADIO_TECHNOLOGY_UMTS);
    }

    /**
     * Provides the name of the algorithmic time zone for the specified
     * offset.  Taken from TimeZone.java.
     */
    private static String displayNameFor(int off) {
        off = off / 1000 / 60;

        char[] buf = new char[9];
        buf[0] = 'G';
        buf[1] = 'M';
        buf[2] = 'T';

        if (off < 0) {
            buf[3] = '-';
            off = -off;
        } else {
            buf[3] = '+';
        }

        int hours = off / 60;
        int minutes = off % 60;

        buf[4] = (char) ('0' + hours / 10);
        buf[5] = (char) ('0' + hours % 10);

        buf[6] = ':';

        buf[7] = (char) ('0' + minutes / 10);
        buf[8] = (char) ('0' + minutes % 10);

        return new String(buf);
    }

    /**
     * nitzReceiveTime is time_t that the NITZ time was posted
     */
    private void setTimeFromNITZString (String nitz, long nitzReceiveTime) {
        // "yy/mm/dd,hh:mm:ss(+/-)tz"
        // tz is in number of quarter-hours

        long start = SystemClock.elapsedRealtime();
        if (DBG) {log("NITZ: " + nitz + "," + nitzReceiveTime +
                        " start=" + start + " delay=" + (start - nitzReceiveTime));
        }

        try {
            /* NITZ time (hour:min:sec) will be in UTC but it supplies the timezone
             * offset as well (which we won't worry about until later) */
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

            c.clear();
            c.set(Calendar.DST_OFFSET, 0);

            String[] nitzSubs = nitz.split("[/:,+-]");

            int year = 2000 + Integer.parseInt(nitzSubs[0]);
            c.set(Calendar.YEAR, year);

            // month is 0 based!
            int month = Integer.parseInt(nitzSubs[1]) - 1;
            c.set(Calendar.MONTH, month);

            int date = Integer.parseInt(nitzSubs[2]);
            c.set(Calendar.DATE, date);

            int hour = Integer.parseInt(nitzSubs[3]);
            c.set(Calendar.HOUR, hour);

            int minute = Integer.parseInt(nitzSubs[4]);
            c.set(Calendar.MINUTE, minute);

            int second = Integer.parseInt(nitzSubs[5]);
            c.set(Calendar.SECOND, second);

            boolean sign = (nitz.indexOf('-') == -1);

            int tzOffset = Integer.parseInt(nitzSubs[6]);

            int dst = (nitzSubs.length >= 8 ) ? Integer.parseInt(nitzSubs[7])
                                              : 0;

            // The zone offset received from NITZ is for current local time,
            // so DST correction is already applied.  Don't add it again.
            //
            // tzOffset += dst * 4;
            //
            // We could unapply it if we wanted the raw offset.

            tzOffset = (sign ? 1 : -1) * tzOffset * 15 * 60 * 1000;

            TimeZone    zone = null;

            // As a special extension, the Android emulator appends the name of
            // the host computer's timezone to the nitz string. this is zoneinfo
            // timezone name of the form Area!Location or Area!Location!SubLocation
            // so we need to convert the ! into /
            if (nitzSubs.length >= 9) {
                String  tzname = nitzSubs[8].replace('!','/');
                zone = TimeZone.getTimeZone( tzname );
            }

            String operatorIsoCountryProperty = PhoneFactory.getProperty(
                    TelephonyProperties.PROPERTY_OPERATOR_ISO_COUNTRY, phone.getPhoneId());
            String iso = SystemProperties.get(operatorIsoCountryProperty);

            if (zone == null) {

                if (mGotCountryCode) {
                    if (iso != null && iso.length() > 0) {
                        zone = TimeUtils.getTimeZone(tzOffset, dst != 0,
                                c.getTimeInMillis(),
                                iso);
                    } else {
                        // We don't have a valid iso country code.  This is
                        // most likely because we're on a test network that's
                        // using a bogus MCC (eg, "001"), so get a TimeZone
                        // based only on the NITZ parameters.
                        zone = getNitzTimeZone(tzOffset, (dst != 0), c.getTimeInMillis());
                    }
                }
            }

            if ((zone == null) || (mZoneOffset != tzOffset) || (mZoneDst != (dst != 0))){
                // We got the time before the country or the zone has changed
                // so we don't know how to identify the DST rules yet.  Save
                // the information and hope to fix it up later.

                mNeedFixZoneAfterNitz = true;
                mZoneOffset  = tzOffset;
                mZoneDst     = dst != 0;
                mZoneTime    = c.getTimeInMillis();
            }

            if (zone != null) {
                if (getAutoTimeZone()) {
                    setAndBroadcastNetworkSetTimeZone(zone.getID());
                }
                saveNitzTimeZone(zone.getID());
            }

            String ignore = SystemProperties.get("gsm.ignore-nitz");
            if (ignore != null && ignore.equals("yes")) {
                log("NITZ: Not setting clock because gsm.ignore-nitz is set");
                return;
            }

            try {
                mWakeLock.acquire();

                if (getAutoTime()) {
                    long millisSinceNitzReceived
                            = SystemClock.elapsedRealtime() - nitzReceiveTime;

                    if (millisSinceNitzReceived < 0) {
                        // Sanity check: something is wrong
                        if (DBG) {
                            log("NITZ: not setting time, clock has rolled "
                                            + "backwards since NITZ time was received, "
                                            + nitz);
                        }
                        return;
                    }

                    if (millisSinceNitzReceived > Integer.MAX_VALUE) {
                        // If the time is this far off, something is wrong > 24 days!
                        if (DBG) {
                            log("NITZ: not setting time, processing has taken "
                                        + (millisSinceNitzReceived / (1000 * 60 * 60 * 24))
                                        + " days");
                        }
                        return;
                    }

                    // Note: with range checks above, cast to int is safe
                    c.add(Calendar.MILLISECOND, (int)millisSinceNitzReceived);

                    if (DBG) {
                        log("NITZ: Setting time of day to " + c.getTime()
                            + " NITZ receive delay(ms): " + millisSinceNitzReceived
                            + " gained(ms): "
                            + (c.getTimeInMillis() - System.currentTimeMillis())
                            + " from " + nitz);
                    }

                    setAndBroadcastNetworkSetTime(c.getTimeInMillis());
                    Log.i(LOG_TAG, "NITZ: after Setting time of day");
                }
                SystemProperties.set("gsm.nitz.time", String.valueOf(c.getTimeInMillis()));
                saveNitzTime(c.getTimeInMillis());
                if (false) {
                    long end = SystemClock.elapsedRealtime();
                    log("NITZ: end=" + end + " dur=" + (end - start));
                }
                mNitzUpdatedTime = true;
            } finally {
                mWakeLock.release();
            }
        } catch (RuntimeException ex) {
            loge("NITZ: Parsing NITZ time " + nitz + " ex=" + ex);
        }
    }

    private boolean getAutoTime() {
        try {
            return Settings.System.getInt(phone.getContext().getContentResolver(),
                    Settings.System.AUTO_TIME) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private boolean getAutoTimeZone() {
        try {
            return Settings.System.getInt(phone.getContext().getContentResolver(),
                    Settings.System.AUTO_TIME_ZONE) > 0;
        } catch (SettingNotFoundException snfe) {
            return true;
        }
    }

    private void saveNitzTimeZone(String zoneId) {
        mSavedTimeZone = zoneId;
    }

    private void saveNitzTime(long time) {
        mSavedTime = time;
        mSavedAtTime = SystemClock.elapsedRealtime();
    }

    /**
     * Set the timezone and send out a sticky broadcast so the system can
     * determine if the timezone was set by the carrier.
     *
     * @param zoneId timezone set by carrier
     */
    private void setAndBroadcastNetworkSetTimeZone(String zoneId) {
        if (DBG) log("setAndBroadcastNetworkSetTimeZone: setTimeZone=" + zoneId);
        AlarmManager alarm =
            (AlarmManager) phone.getContext().getSystemService(Context.ALARM_SERVICE);
        alarm.setTimeZone(zoneId);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIMEZONE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time-zone", zoneId);
        phone.getContext().sendStickyBroadcast(intent);
        if (DBG) {
            log("setAndBroadcastNetworkSetTimeZone: call alarm.setTimeZone and broadcast zoneId=" +
                zoneId);
        }
    }

    /**
     * Set the time and Send out a sticky broadcast so the system can determine
     * if the time was set by the carrier.
     *
     * @param time time set by network
     */
    private void setAndBroadcastNetworkSetTime(long time) {
        if (DBG) log("setAndBroadcastNetworkSetTime: time=" + time + "ms");
        SystemClock.setCurrentTimeMillis(time);
        Intent intent = new Intent(TelephonyIntents.ACTION_NETWORK_SET_TIME);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("time", time);
        phone.getContext().sendStickyBroadcast(intent);
    }

    private void revertToNitzTime() {
        if (Settings.System.getInt(phone.getContext().getContentResolver(),
                Settings.System.AUTO_TIME, 0) == 0) {
            return;
        }
        if (DBG) {
            log("Reverting to NITZ Time: mSavedTime=" + mSavedTime
                + " mSavedAtTime=" + mSavedAtTime);
        }
        if (mSavedTime != 0 && mSavedAtTime != 0) {
            setAndBroadcastNetworkSetTime(mSavedTime
                    + (SystemClock.elapsedRealtime() - mSavedAtTime));
        }
    }

    private void revertToNitzTimeZone() {
        if (Settings.System.getInt(phone.getContext().getContentResolver(),
                Settings.System.AUTO_TIME_ZONE, 0) == 0) {
            return;
        }
        if (DBG) log("Reverting to NITZ TimeZone: tz='" + mSavedTimeZone);
        if (mSavedTimeZone != null) {
            setAndBroadcastNetworkSetTimeZone(mSavedTimeZone);
        }
    }

    /**
     * Post a notification to NotificationManager for restricted state
     *
     * @param notifyType is one state of PS/CS_*_ENABLE/DISABLE
     */
    private void setNotification(int notifyType) {

        if (DBG) log("setNotification: create notification " + notifyType);
        Context context = phone.getContext();

        mNotification = new Notification();
        mNotification.when = System.currentTimeMillis();
        mNotification.flags = Notification.FLAG_AUTO_CANCEL;
        mNotification.icon = com.android.internal.R.drawable.stat_sys_warning;
        Intent intent = new Intent();
        mNotification.contentIntent = PendingIntent
        .getActivity(context, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

        CharSequence details = "";
        CharSequence title = context.getText(com.android.internal.R.string.RestrictedChangedTitle);
        int notificationId = CS_NOTIFICATION;

        switch (notifyType) {
        case PS_ENABLED:
            notificationId = PS_NOTIFICATION;
            details = context.getText(com.android.internal.R.string.RestrictedOnData);;
            break;
        case PS_DISABLED:
            notificationId = PS_NOTIFICATION;
            break;
        case CS_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnAllVoice);;
            break;
        case CS_NORMAL_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnNormal);;
            break;
        case CS_EMERGENCY_ENABLED:
            details = context.getText(com.android.internal.R.string.RestrictedOnEmergency);;
            break;
        case CS_DISABLED:
            // do nothing and cancel the notification later
            break;
        }

        if (DBG) log("setNotification: put notification " + title + " / " +details);
        mNotification.tickerText = title;
        mNotification.setLatestEventInfo(context, title, details,
                mNotification.contentIntent);

        NotificationManager notificationManager = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (notifyType == PS_DISABLED || notifyType == CS_DISABLED) {
            // cancel previous post notification
            notificationManager.cancel(notificationId);
        } else {
            // update restricted state notification
            notificationManager.notify(notificationId, mNotification);
        }
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[GsmSST]-phoneId" + phone.getPhoneId() + "] " + s);
    }

    @Override
    protected void loge(String s) {
        Log.e(LOG_TAG, "[GsmSST]-phoneId" + phone.getPhoneId() + "] " + s);
    }

    private static void sloge(String s) {
        Log.e(LOG_TAG, "[GsmSST] " + s);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("GsmServiceStateTracker extends:");
        super.dump(fd, pw, args);
        pw.println(" phone=" + phone);
        pw.println(" cellLoc=" + cellLoc);
        pw.println(" newCellLoc=" + newCellLoc);
        pw.println(" mPreferredNetworkType=" + mPreferredNetworkType);
        pw.println(" gprsState=" + gprsState);
        pw.println(" newGPRSState=" + newGPRSState);
        pw.println(" mMaxDataCalls=" + mMaxDataCalls);
        pw.println(" mNewMaxDataCalls=" + mNewMaxDataCalls);
        pw.println(" mReasonDataDenied=" + mReasonDataDenied);
        pw.println(" mNewReasonDataDenied=" + mNewReasonDataDenied);
        pw.println(" mGsmRoaming=" + mGsmRoaming);
        pw.println(" mDataRoaming=" + mDataRoaming);
        pw.println(" mEmergencyOnly=" + mEmergencyOnly);
        pw.println(" mNeedFixZoneAfterNitz=" + mNeedFixZoneAfterNitz);
        pw.println(" mZoneOffset=" + mZoneOffset);
        pw.println(" mZoneDst=" + mZoneDst);
        pw.println(" mZoneTime=" + mZoneTime);
        pw.println(" mGotCountryCode=" + mGotCountryCode);
        pw.println(" mNitzUpdatedTime=" + mNitzUpdatedTime);
        pw.println(" mSavedTimeZone=" + mSavedTimeZone);
        pw.println(" mSavedTime=" + mSavedTime);
        pw.println(" mSavedAtTime=" + mSavedAtTime);
        pw.println(" mNeedToRegForSimLoaded=" + mNeedToRegForSimLoaded);
        pw.println(" mStartedGprsRegCheck=" + mStartedGprsRegCheck);
        pw.println(" mReportedGprsNoReg=" + mReportedGprsNoReg);
        pw.println(" mNotification=" + mNotification);
        pw.println(" mWakeLock=" + mWakeLock);
        pw.println(" curSpn=" + curSpn);
        pw.println(" curPlmn=" + curPlmn);
        pw.println(" curSpnRule=" + curSpnRule);
    }
}
