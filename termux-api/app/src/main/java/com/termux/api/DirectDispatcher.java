package com.termux.api;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.content.Intent;
import android.provider.Settings;
import android.util.JsonWriter;
import android.widget.Toast;

import com.termux.api.apis.AudioAPI;
import com.termux.api.apis.BluetoothAPI;
import com.termux.api.apis.BatteryStatusAPI;
import com.termux.api.apis.BrightnessAPI;
import com.termux.api.apis.CallLogAPI;
import com.termux.api.apis.CameraInfoAPI;
import com.termux.api.apis.CameraPhotoAPI;
import com.termux.api.apis.ClipboardAPI;
import com.termux.api.apis.ContactListAPI;
import com.termux.api.apis.DialogAPI;
import com.termux.api.apis.CompanionAPI;
import com.termux.api.apis.DownloadAPI;
import com.termux.api.apis.FingerprintAPI;
import com.termux.api.apis.InfraredAPI;
import com.termux.api.apis.PermissionAPI;
import com.termux.api.apis.JobSchedulerAPI;
import com.termux.api.apis.KeystoreAPI;
import com.termux.api.apis.LocationAPI;
import com.termux.api.apis.MediaPlayerAPI;
import com.termux.api.apis.MediaScannerAPI;
import com.termux.api.apis.MicRecorderAPI;
import com.termux.api.apis.NsdAPI;
import com.termux.api.apis.NfcAPI;
import com.termux.api.apis.NotificationAPI;
import com.termux.api.apis.NotificationListAPI;
import com.termux.api.apis.SAFAPI;
import com.termux.api.apis.SensorAPI;
import com.termux.api.apis.ShareAPI;
import com.termux.api.apis.SmsInboxAPI;
import com.termux.api.apis.SmsSendAPI;
import com.termux.api.apis.SpeechToTextAPI;
import com.termux.api.apis.StorageGetAPI;
import com.termux.api.apis.TelephonyAPI;
import com.termux.api.apis.TextToSpeechAPI;
import com.termux.api.apis.ToastAPI;
import com.termux.api.apis.TorchAPI;
import com.termux.api.apis.UsbAPI;
import com.termux.api.apis.VibrateAPI;
import com.termux.api.apis.VolumeAPI;
import com.termux.api.apis.WallpaperAPI;
import com.termux.api.apis.WifiAPI;
import com.termux.api.activities.TermuxApiPermissionActivity;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

/**
 * Dispatches API method calls to the appropriate handler.
 * Used by both {@link TermuxApiReceiver} (broadcast path) and
 * {@link SocketListener} (direct zero-latency path).
 *
 * <p>The {@code handlerContext} parameter replaces the BroadcastReceiver
 * reference that handlers used to receive. In the broadcast path it is the
 * {@code TermuxApiReceiver} instance; in the direct path it is the application
 * {@link Context} (or a dummy receiver). {@link com.termux.api.util.ResultReturner}
 * checks {@link android.content.BroadcastReceiver#isOrderedBroadcast()} and
 * only calls {@code goAsync()} when a real ordered broadcast is active.</p>
 */
public final class DirectDispatcher {

    private static final String LOG_TAG = "DirectDispatcher";

    private DirectDispatcher() {
        // Utility class — no instances
    }

    /**
     * Dispatch an API call to the appropriate typed handler.
     *
     * @param handlerContext {@code TermuxApiReceiver} to pass as the first
     *                       parameter to handlers (the real receiver for
     *                       broadcast path, a non-registered stub for direct
     *                       path so that {@code isOrderedBroadcast()} returns
     *                       false and {@code goAsync()} is skipped)
     * @param context       Android context for system services
     * @param intent        intent containing {@code api_method} and all extras
     */
    public static void dispatch(TermuxApiReceiver handlerContext, Context context, Intent intent) {
        String apiMethod = intent.getStringExtra("api_method");
        if (apiMethod == null) {
            Logger.logError(LOG_TAG, "Missing 'api_method' extra");
            replyError(handlerContext, intent, "Missing 'api_method' extra");
            return;
        }

        switch (apiMethod) {
            case "Permission":
                PermissionAPI.onReceive(context, intent);
                break;
            case "AudioInfo":
                AudioAPI.onReceive(handlerContext, context, intent);
                break;
            case "Bluetooth":
                // The permission set comes from the handler itself
                // (BluetoothAPI.requiredPermissions) so the two gates cannot
                // drift apart. They did: this block asked for CONNECT+SCAN only,
                // so `advertise` was dispatched with BLUETOOTH_ADVERTISE still
                // ungranted, told the handler's own gate "advertise needs
                // ADVERTISE", and that gate then requested CONNECT+SCAN - an
                // already-granted set - which writes no answer at all. The
                // client timed out with "Handler did not produce a response".
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    String btCommand = intent.getStringExtra("command");
                    if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                            BluetoothAPI.requiredPermissions(btCommand))) {
                        BluetoothAPI.onReceive(handlerContext, context, intent);
                    }
                } else {
                    if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                            Manifest.permission.BLUETOOTH,
                            Manifest.permission.BLUETOOTH_ADMIN)) {
                        BluetoothAPI.onReceive(handlerContext, context, intent);
                    }
                }
                break;
            case "BatteryStatus":
                BatteryStatusAPI.onReceive(handlerContext, context, intent);
                break;
            case "Brightness":
                // No hard gate on WRITE_SETTINGS here. It is a "special app
                // access" permission: Android has no Allow/Deny dialog for it,
                // and Termux:API never required it explicitly either - the
                // write succeeds whenever the platform grants it and the
                // readback proves it. Refusing up front broke the command on
                // devices where the write is allowed but canWrite() answers
                // false. BrightnessAPI does the write, reads the value back
                // and reports the real outcome (including the missing
                // special access) from that.
                BrightnessAPI.onReceive(handlerContext, context, intent);
                break;
            case "CameraInfo":
                CameraInfoAPI.onReceive(handlerContext, context, intent);
                break;
            case "CameraPhoto":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CAMERA)) {
                    CameraPhotoAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "CallLog":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CALL_LOG)) {
                    CallLogAPI.onReceive(context, intent);
                }
                break;
            case "Clipboard":
                ClipboardAPI.onReceive(handlerContext, context, intent);
                break;
            case "ContactList":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CONTACTS)) {
                    ContactListAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "Dialog":
                DialogAPI.onReceive(context, intent);
                break;
            case "Download":
                DownloadAPI.onReceive(handlerContext, context, intent);
                break;
            case "Fingerprint":
                FingerprintAPI.onReceive(context, intent);
                break;
            case "InfraredFrequencies":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveCarrierFrequency(handlerContext, context, intent);
                }
                break;
            case "InfraredTransmit":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveTransmit(handlerContext, context, intent);
                }
                break;
            case "JobScheduler":
                JobSchedulerAPI.onReceive(handlerContext, context, intent);
                break;
            case "Keystore":
                KeystoreAPI.onReceive(handlerContext, intent);
                break;
            case "Location":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    LocationAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "MediaPlayer":
                MediaPlayerAPI.onReceive(context, intent);
                break;
            case "MediaScanner":
                MediaScannerAPI.onReceive(handlerContext, context, intent);
                break;
            case "MicRecorder":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    MicRecorderAPI.onReceive(context, intent);
                }
                break;
            case "Nfc":
                NfcAPI.onReceive(context, intent);
                break;
            case "Nsd":
                NsdAPI.onReceive(handlerContext, context, intent);
                break;
            case "Companion":
                CompanionAPI.onReceive(handlerContext, context, intent);
                break;
            case "Notification":
                NotificationAPI.onReceiveShowNotification(handlerContext, context, intent);
                break;
            case "NotificationChannel":
                NotificationAPI.onReceiveChannel(handlerContext, context, intent);
                break;
            case "NotificationList":
                ComponentName cn = new ComponentName(context, NotificationListAPI.NotificationService.class);
                String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
                boolean NotificationServiceEnabled = flat != null && flat.contains(cn.flattenToString());
                if (!NotificationServiceEnabled) {
                    Toast.makeText(context, "Please give Termux:API Notification Access", Toast.LENGTH_LONG).show();
                    context.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {
                    NotificationListAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "NotificationRemove":
                NotificationAPI.onReceiveRemoveNotification(handlerContext, context, intent);
                break;
            case "NotificationReply":
                NotificationAPI.onReceiveReplyToNotification(handlerContext, context, intent);
                break;
            case "SAF":
                SAFAPI.onReceive(handlerContext, context, intent);
                break;
            case "Sensor": {
                String[] sensorPerms = SensorAPI.requiredPermissions(context, intent);
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, sensorPerms)) {
                    SensorAPI.onReceive(context, intent);
                }
                break;
            }
            case "Share":
                ShareAPI.onReceive(handlerContext, context, intent);
                break;
            case "SmsInbox":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_SMS)) {
                    SmsInboxAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "SmsSend":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS)) {
                    SmsSendAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "SpeechToText":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    SpeechToTextAPI.onReceive(context, intent);
                }
                break;
            case "StorageGet":
                StorageGetAPI.onReceive(handlerContext, context, intent);
                break;
            case "TelephonyCall":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CALL_PHONE)) {
                    TelephonyAPI.onReceiveTelephonyCall(handlerContext, context, intent);
                }
                break;
            case "TelephonyCellInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    TelephonyAPI.onReceiveTelephonyCellInfo(handlerContext, context, intent);
                }
                break;
            case "TelephonyDeviceInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE)) {
                    TelephonyAPI.onReceiveTelephonyDeviceInfo(handlerContext, context, intent);
                }
                break;
            case "TextToSpeech":
                TextToSpeechAPI.onReceive(context, intent);
                break;
            case "Toast":
                ToastAPI.onReceive(context, intent);
                break;
            case "Torch":
                TorchAPI.onReceive(handlerContext, context, intent);
                break;
            case "Usb":
                UsbAPI.onReceive(context, intent);
                break;
            case "Vibrate":
                VibrateAPI.onReceive(handlerContext, context, intent);
                break;
            case "Volume":
                VolumeAPI.onReceive(handlerContext, context, intent);
                break;
            case "Wallpaper":
                WallpaperAPI.onReceive(context, intent);
                break;
            // ── WiFi commands (unified dispatch via command extra) ──────────
            case "WifiConnectionInfo": {
                Intent wrapped = new Intent(intent);
                wrapped.putExtra("command", "connection-info");
                WifiAPI.onReceive(handlerContext, context, wrapped);
                break;
            }
            case "WifiScanInfo": {
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    Intent wrapped = new Intent(intent);
                    wrapped.putExtra("command", "scan-info");
                    WifiAPI.onReceive(handlerContext, context, wrapped);
                }
                break;
            }
            case "WifiEnable": {
                Intent wrapped = new Intent(intent);
                wrapped.putExtra("command", "enable");
                WifiAPI.onReceive(handlerContext, context, wrapped);
                break;
            }
            case "Wifi":
                WifiAPI.onReceive(handlerContext, context, intent);
                break;
            default:
                // Answer instead of returning silently: SocketListener has no
                // way to know a dispatch was refused, so a bare return became
                // "Handler did not produce a response" 30 s later.
                Logger.logError(LOG_TAG, "Unrecognized 'api_method' extra: '" + apiMethod + "'");
                replyError(handlerContext, intent,
                        "Unrecognized api_method: " + apiMethod);
        }
    }

    /** Write an error JSON to the caller, on both the direct and broadcast paths. */
    private static void replyError(Object receiver, Intent intent, String message) {
        try {
            ResultReturner.returnData(receiver, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("error").value(message);
                    out.endObject();
                }
            });
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to return dispatch error", e);
        }
    }
}
