/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.telecomm.CallState;
import android.telephony.DisconnectCause;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;

/**
 * Creates a notification for calls that the user missed (neither answered nor rejected).
 * TODO(santoscordon): Make TelephonyManager.clearMissedCalls call into this class.
 * STOPSHIP: Resolve b/13769374 about moving this class to InCall.
 */
class MissedCallNotifier extends CallsManagerListenerBase {

    private static final int MISSED_CALL_NOTIFICATION_ID = 1;
    private static final String SCHEME_SMSTO = "smsto";

    private final Context mContext;
    private final NotificationManager mNotificationManager;

    // Used to track the number of missed calls.
    private int mMissedCallCount = 0;

    MissedCallNotifier(Context context) {
        mContext = context;
        mNotificationManager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /** {@inheritDoc} */
    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        if (oldState == CallState.RINGING && newState == CallState.DISCONNECTED &&
                call.getDisconnectCause() == DisconnectCause.INCOMING_MISSED) {
            showMissedCallNotification(call);
        }
    }

    /** Clears missed call notification and marks the call log's missed calls as read. */
    void clearMissedCalls() {
        // Clear the list of new missed calls from the call log.
        ContentValues values = new ContentValues();
        values.put(Calls.NEW, 0);
        values.put(Calls.IS_READ, 1);
        StringBuilder where = new StringBuilder();
        where.append(Calls.NEW);
        where.append(" = 1 AND ");
        where.append(Calls.TYPE);
        where.append(" = ?");
        mContext.getContentResolver().update(Calls.CONTENT_URI, values, where.toString(),
                new String[]{ Integer.toString(Calls.MISSED_TYPE) });

        cancelMissedCallNotification();
    }

    /**
     * Create a system notification for the missed call.
     *
     * @param call The missed call.
     */
    private void showMissedCallNotification(Call call) {
        mMissedCallCount++;

        final int titleResId;
        final String expandedText;  // The text in the notification's line 1 and 2.

        // Display the first line of the notification:
        // 1 missed call: <caller name || handle>
        // More than 1 missed call: <number of calls> + "missed calls"
        if (mMissedCallCount == 1) {
            titleResId = R.string.notification_missedCallTitle;
            expandedText = getNameForCall(call);
        } else {
            titleResId = R.string.notification_missedCallsTitle;
            expandedText =
                    mContext.getString(R.string.notification_missedCallsMsg, mMissedCallCount);
        }

        // Create the notification.
        Notification.Builder builder = new Notification.Builder(mContext);
        builder.setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setWhen(call.getCreationTimeMillis())
                .setContentTitle(mContext.getText(titleResId))
                .setContentText(expandedText)
                .setContentIntent(createCallLogPendingIntent())
                .setAutoCancel(true)
                .setDeleteIntent(createClearMissedCallsPendingIntent());

        Uri handleUri = call.getHandle();
        String handle = handleUri.getSchemeSpecificPart();

        // Add additional actions when there is only 1 missed call, like call-back and SMS.
        if (mMissedCallCount == 1) {
            Log.d(this, "Add actions with number %s.", Log.piiHandle(handle));

            builder.addAction(R.drawable.stat_sys_phone_call,
                    mContext.getString(R.string.notification_missedCall_call_back),
                    createCallBackPendingIntent(handleUri));

            builder.addAction(R.drawable.ic_text_holo_dark,
                    mContext.getString(R.string.notification_missedCall_message),
                    createSendSmsFromNotificationPendingIntent(handleUri));

            Bitmap photoIcon = call.getPhotoIcon();
            if (photoIcon != null) {
                builder.setLargeIcon(photoIcon);
            } else {
                Drawable photo = call.getPhoto();
                if (photo != null && photo instanceof BitmapDrawable) {
                    builder.setLargeIcon(((BitmapDrawable) photo).getBitmap());
                }
            }
        } else {
            Log.d(this, "Suppress actions. handle: %s, missedCalls: %d.", Log.piiHandle(handle),
                    mMissedCallCount);
        }

        Notification notification = builder.build();
        configureLedOnNotification(notification);

        Log.i(this, "Adding missed call notification for %s.", call);
        mNotificationManager.notify(MISSED_CALL_NOTIFICATION_ID, notification);
    }

    /** Cancels the "missed call" notification. */
    private void cancelMissedCallNotification() {
        // Reset the number of missed calls to 0.
        mMissedCallCount = 0;
        mNotificationManager.cancel(MISSED_CALL_NOTIFICATION_ID);
    }

    /**
     * Returns the name to use in the missed call notification.
     */
    private String getNameForCall(Call call) {
        String handle = call.getHandle().getSchemeSpecificPart();
        String name = call.getName();

        if (!TextUtils.isEmpty(name) && TextUtils.isGraphic(name)) {
            return name;
        } else if (!TextUtils.isEmpty(handle)) {
            // A handle should always be displayed LTR using {@link BidiFormatter} regardless of the
            // content of the rest of the notification.
            // TODO(santoscordon): Does this apply to SIP addresses?
            BidiFormatter bidiFormatter = BidiFormatter.getInstance();
            return bidiFormatter.unicodeWrap(handle, TextDirectionHeuristics.LTR);
        } else {
            // Use "unknown" if the call is unidentifiable.
            return mContext.getString(R.string.unknown);
        }
    }

    /**
     * Creates a new pending intent that sends the user to the call log.
     *
     * @return The pending intent.
     */
    private PendingIntent createCallLogPendingIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, null);
        intent.setType(CallLog.Calls.CONTENT_TYPE);

        TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(mContext);
        taskStackBuilder.addNextIntent(intent);

        return taskStackBuilder.getPendingIntent(0, 0);
    }

    /**
     * Creates an intent to be invoked when the missed call notification is cleared.
     */
    private PendingIntent createClearMissedCallsPendingIntent() {
        return createTelecommPendingIntent(
                TelecommBroadcastReceiver.ACTION_CLEAR_MISSED_CALLS, null);
    }

    /**
     * Creates an intent to be invoked when the user opts to "call back" from the missed call
     * notification.
     *
     * @param handle The handle to call back.
     */
    private PendingIntent createCallBackPendingIntent(Uri handle) {
        return createTelecommPendingIntent(
                TelecommBroadcastReceiver.ACTION_CALL_BACK_FROM_NOTIFICATION, handle);
    }

    /**
     * Creates an intent to be invoked when the user opts to "send sms" from the missed call
     * notification.
     */
    private PendingIntent createSendSmsFromNotificationPendingIntent(Uri handle) {
        return createTelecommPendingIntent(
                TelecommBroadcastReceiver.ACTION_SEND_SMS_FROM_NOTIFICATION,
                Uri.fromParts(SCHEME_SMSTO, handle.getSchemeSpecificPart(), null));
    }

    /**
     * Creates generic pending intent from the specified parameters to be received by
     * {@link TelecommBroadcastReceiver}.
     *
     * @param action The intent action.
     * @param data The intent data.
     */
    private PendingIntent createTelecommPendingIntent(String action, Uri data) {
        Intent intent = new Intent(action, data, mContext, TelecommBroadcastReceiver.class);
        return PendingIntent.getBroadcast(mContext, 0, intent, 0);
    }

    /**
     * Configures a notification to emit the blinky notification light.
     */
    private void configureLedOnNotification(Notification notification) {
        notification.flags |= Notification.FLAG_SHOW_LIGHTS;
        notification.defaults |= Notification.DEFAULT_LIGHTS;
    }
}