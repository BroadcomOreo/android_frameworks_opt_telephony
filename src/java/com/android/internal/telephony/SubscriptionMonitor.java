/*
* Copyright (C) 2015 The Android Open Source Project
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

import static android.telephony.SubscriptionManager.INVALID_PHONE_INDEX;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.ISub;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;

import java.lang.IllegalArgumentException;

/**
 * Utility singleton to monitor subscription changes and help people act on them.
 * Uses Registrant model to post messages to handlers.
 *
 */
public class SubscriptionMonitor {

    private final RegistrantList mSubscriptionsChangedRegistrants[];
    private final RegistrantList mDefaultDataSubChangedRegistrants[];

    private final SubscriptionController mSubscriptionController;
    private final Context mContext;

    private final int mPhoneSubId[];
    private int mDefaultDataSubId;
    private int mDefaultDataPhoneId;

    private final Object mLock = new Object();

    private final static boolean VDBG = true;
    private final static String LOG_TAG = "SubscriptionMonitor";

    public SubscriptionMonitor(ITelephonyRegistry tr, Context context,
            SubscriptionController subscriptionController, int numPhones) {
        try {
            tr.addOnSubscriptionsChangedListener("SubscriptionMonitor",
                    mSubscriptionsChangedListener);
        } catch (RemoteException e) {
        }

        mSubscriptionController = subscriptionController;
        mContext = context;

        mSubscriptionsChangedRegistrants = new RegistrantList[numPhones];
        mDefaultDataSubChangedRegistrants = new RegistrantList[numPhones];
        mPhoneSubId = new int[numPhones];

        mDefaultDataSubId = mSubscriptionController.getDefaultDataSubId();
        mDefaultDataPhoneId = mSubscriptionController.getPhoneId(mDefaultDataSubId);

        for (int phoneId = 0; phoneId < numPhones; phoneId++) {
            mSubscriptionsChangedRegistrants[phoneId] = new RegistrantList();
            mDefaultDataSubChangedRegistrants[phoneId] = new RegistrantList();
            mPhoneSubId[phoneId] = mSubscriptionController.getSubIdUsingPhoneId(phoneId);
        }

        mContext.registerReceiver(mDefaultDataSubscriptionChangedReceiver,
                new IntentFilter(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED));
    }

    @VisibleForTesting
    public SubscriptionMonitor() {
        mSubscriptionsChangedRegistrants = null;
        mDefaultDataSubChangedRegistrants = null;
        mSubscriptionController = null;
        mContext = null;
        mPhoneSubId = null;
    }

    private final IOnSubscriptionsChangedListener mSubscriptionsChangedListener =
            new IOnSubscriptionsChangedListener.Stub() {
        @Override
        public void onSubscriptionsChanged() {
            if (VDBG) log("onSubscriptionsChanged");
            synchronized (mLock) {
                for (int phoneId = 0; phoneId < mPhoneSubId.length; phoneId++) {
                    final int newSubId = mSubscriptionController.getSubIdUsingPhoneId(phoneId);
                    final int oldSubId = mPhoneSubId[phoneId];
                    if (oldSubId != newSubId) {
                        if (VDBG) {
                            log("Phone[" + phoneId + "] subId changed " + oldSubId + "->"
                                    + newSubId);
                        }
                        mPhoneSubId[phoneId] = newSubId;
                        mSubscriptionsChangedRegistrants[phoneId].notifyRegistrants();

                        // if the default isn't set, just move along..
                        if (mDefaultDataSubId == INVALID_SUBSCRIPTION_ID) continue;

                        // check if this affects default data
                        if (newSubId == mDefaultDataSubId || oldSubId == mDefaultDataSubId) {
                            if (VDBG) log("mDefaultDataSubId = " + mDefaultDataSubId);
                            mDefaultDataSubChangedRegistrants[phoneId].notifyRegistrants();
                            if (newSubId == mDefaultDataSubId) {
                                mDefaultDataPhoneId = phoneId;
                            }
                        }
                    }
                }
            }
        }
    };

    private final BroadcastReceiver mDefaultDataSubscriptionChangedReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int newDefaultDataSubId = mSubscriptionController.getDefaultDataSubId();
            synchronized (mLock) {
                if (mDefaultDataSubId != newDefaultDataSubId) {
                    if (VDBG) log("Got Default changed " + mDefaultDataSubId + "->" +
                            newDefaultDataSubId);
                    final int oldDefaultDataSubId = mDefaultDataSubId;
                    final int oldDefaultDataPhoneId = mDefaultDataPhoneId;
                    if (VDBG) {
                        log("oldDefaultDataSubId=" + mDefaultDataSubId +
                                ", oldDefaultDataPhoneId=" + mDefaultDataPhoneId);
                    }
                    mDefaultDataSubId = newDefaultDataSubId;

                    int newDefaultDataPhoneId =
                            mSubscriptionController.getPhoneId(INVALID_SUBSCRIPTION_ID);
                    if (newDefaultDataSubId != INVALID_SUBSCRIPTION_ID) {
                        for (int phoneId = 0; phoneId < mPhoneSubId.length; phoneId++) {
                            if (mPhoneSubId[phoneId] == newDefaultDataSubId) {
                                newDefaultDataPhoneId = phoneId;
                                if (VDBG) log("newDefaultDataPhoneId=" + newDefaultDataPhoneId);
                                break;
                            }
                        }
                    }

                    if (newDefaultDataPhoneId != oldDefaultDataPhoneId) {
                        mDefaultDataPhoneId = newDefaultDataPhoneId;
                        if (!invalidPhoneId(oldDefaultDataPhoneId)) {
                            mDefaultDataSubChangedRegistrants[oldDefaultDataPhoneId].
                                    notifyRegistrants();
                        }
                        if (!invalidPhoneId(newDefaultDataPhoneId)) {
                            mDefaultDataSubChangedRegistrants[newDefaultDataPhoneId].
                                    notifyRegistrants();
                        }
                    }
                }
            }
        }
    };

    public void registerForSubscriptionChanged(int phoneId, Handler h, int what, Object o) {
        if (invalidPhoneId(phoneId)) {
            throw new IllegalArgumentException("Invalid PhoneId");
        }
        Registrant r = new Registrant(h, what, o);
        mSubscriptionsChangedRegistrants[phoneId].add(r);
        r.notifyRegistrant();
    }

    public void unregisterForSubscriptionChanged(int phoneId, Handler h) {
        if (invalidPhoneId(phoneId)) {
            throw new IllegalArgumentException("Invalid PhoneId");
        }
        mSubscriptionsChangedRegistrants[phoneId].remove(h);
    }

    public void registerForDefaultDataSubscriptionChanged(int phoneId, Handler h, int what,
            Object o) {
        if (invalidPhoneId(phoneId)) {
            throw new IllegalArgumentException("Invalid PhoneId");
        }
        Registrant r = new Registrant(h, what, o);
        mDefaultDataSubChangedRegistrants[phoneId].add(r);
        r.notifyRegistrant();
    }

    public void unregisterForDefaultDataSubscriptionChanged(int phoneId, Handler h) {
        if (invalidPhoneId(phoneId)) {
            throw new IllegalArgumentException("Invalid PhoneId");
        }
        mDefaultDataSubChangedRegistrants[phoneId].remove(h);
    }

    private boolean invalidPhoneId(int phoneId) {
        if (phoneId >= 0 && phoneId < mPhoneSubId.length) return false;
        return true;
    }

    private void log(String s) {
        Rlog.d(LOG_TAG, s);
    }
}
