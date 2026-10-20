package com.termux.api.apis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.JsonWriter;

import com.termux.api.activities.PermissionPromptActivity;
import com.termux.api.util.ResultReturner;
import com.termux.shared.android.PermissionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The "permission" API method: reports which permissions Termux:API holds and
 * asks for the missing ones through an in-app prompt, so the user never has to
 * hunt through the system settings app.
 *
 * Three kinds of permission exist and they are not interchangeable:
 *
 *   runtime     granted by the standard Allow/Deny dialog (Android 6+)
 *   settings    "special app access" - only grantable from a dedicated settings
 *               page, but we can open that exact page for the user
 *   privileged  signature|privileged - cannot be granted at runtime at all; the
 *               app must be installed as a priv-app with a whitelist XML.  The
 *               prompt explains this instead of pretending a dialog exists.
 */
public class PermissionAPI {

    public static final String KIND_RUNTIME = "runtime";
    public static final String KIND_SETTINGS = "settings";
    public static final String KIND_PRIVILEGED = "privileged";

    /** One permission Termux:API may need, plus how the user can grant it. */
    public static class Entry {
        public final String key;
        public final String permission;
        public final String label;
        public final String kind;
        public final String settingsAction;
        public final String howTo;

        Entry(String key, String permission, String label, String kind, String settingsAction, String howTo) {
            this.key = key;
            this.permission = permission;
            this.label = label;
            this.kind = kind;
            this.settingsAction = settingsAction;
            this.howTo = howTo;
        }
    }

    private static Entry runtime(String key, String permission, String label) {
        return new Entry(key, permission, label, KIND_RUNTIME, null,
            "A dialog appears; tap Allow.");
    }

    private static Entry settings(String key, String permission, String label, String action, String howTo) {
        return new Entry(key, permission, label, KIND_SETTINGS, action, howTo);
    }

    private static Entry privileged(String key, String permission, String label) {
        return new Entry(key, permission, label, KIND_PRIVILEGED, null,
            "Cannot be granted at runtime: the app must be installed as a priv-app with a whitelist XML (root required).");
    }

    /** Everything the API surface in this repo can ask for. */
    public static List<Entry> catalog() {
        List<Entry> list = new ArrayList<>();

        // Bluetooth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(runtime("bluetooth_scan", Manifest.permission.BLUETOOTH_SCAN, "Scan for nearby Bluetooth devices"));
            list.add(runtime("bluetooth_connect", Manifest.permission.BLUETOOTH_CONNECT, "Connect to paired Bluetooth devices"));
            list.add(runtime("bluetooth_advertise", Manifest.permission.BLUETOOTH_ADVERTISE, "Advertise as a Bluetooth device"));
        }
        list.add(runtime("fine_location", Manifest.permission.ACCESS_FINE_LOCATION, "Precise location (also required for WiFi/Bluetooth scans on older Android)"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(runtime("nearby_wifi", Manifest.permission.NEARBY_WIFI_DEVICES, "Find and connect to nearby WiFi networks"));
        }

        // Media / hardware
        list.add(runtime("camera", Manifest.permission.CAMERA, "Take photos and record video"));
        list.add(runtime("microphone", Manifest.permission.RECORD_AUDIO, "Record audio"));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(runtime("notifications", Manifest.permission.POST_NOTIFICATIONS, "Post notifications"));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(runtime("sensors_body", Manifest.permission.BODY_SENSORS, "Read body sensors (heart rate)"));
        }

        // Personal data
        list.add(runtime("contacts", Manifest.permission.READ_CONTACTS, "Read contacts"));
        list.add(runtime("sms", Manifest.permission.READ_SMS, "Read SMS messages"));
        list.add(runtime("call_log", Manifest.permission.READ_CALL_LOG, "Read the call log"));
        list.add(runtime("phone_state", Manifest.permission.READ_PHONE_STATE, "Read phone state (IMEI, network, call state)"));
        list.add(runtime("call_phone", Manifest.permission.CALL_PHONE, "Place phone calls"));

        // Storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(runtime("media_images", Manifest.permission.READ_MEDIA_IMAGES, "Read image files"));
            list.add(runtime("media_audio", Manifest.permission.READ_MEDIA_AUDIO, "Read audio files"));
            list.add(runtime("media_video", Manifest.permission.READ_MEDIA_VIDEO, "Read video files"));
        } else {
            list.add(runtime("storage", Manifest.permission.READ_EXTERNAL_STORAGE, "Read shared storage"));
        }

        // Special app access - a dedicated settings page, opened for the user.
        list.add(settings("write_settings", Manifest.permission.WRITE_SETTINGS,
            "Change system settings (brightness, volume, ring mode)",
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            "Opens the \"Modify system settings\" page; toggle Texmux on."));
        list.add(settings("overlay", Manifest.permission.SYSTEM_ALERT_WINDOW,
            "Draw over other apps (termux-dialog overlays)",
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "Opens the \"Display over other apps\" page; toggle Texmux on."));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list.add(settings("all_files", Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                "Manage all files on shared storage",
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                "Opens the \"All files access\" page; toggle Texmux on."));
        }
        list.add(settings("battery_unrestricted", Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "Run without battery optimisation (keeps background work alive)",
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "Opens the battery optimisation dialog; choose Allow."));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            list.add(settings("dnd_access", Manifest.permission.ACCESS_NOTIFICATION_POLICY,
                "Change Do Not Disturb state",
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                "Opens the \"Do Not Disturb access\" page; toggle Texmux on."));
        }

        // Privileged only - priv-app + whitelist XML, no dialog can exist.
        list.add(privileged("tether_privileged", "android.permission.TETHER_PRIVILEGED",
            "Turn the system hotspot on/off (termux-wifi hotspot-enable)"));
        list.add(privileged("network_settings", "android.permission.NETWORK_SETTINGS",
            "Read and change network configuration"));
        list.add(privileged("bluetooth_privileged", "android.permission.BLUETOOTH_PRIVILEGED",
            "Silently pair/unpair and connect classic Bluetooth profiles"));
        list.add(privileged("modify_phone_state", "android.permission.MODIFY_PHONE_STATE",
            "Change radio state (mobile data, airplane mode)"));

        return list;
    }

    public static Entry findByKey(String key) {
        for (Entry entry : catalog()) {
            if (entry.key.equals(key)) return entry;
        }
        return null;
    }

    public static boolean isGranted(Context context, Entry entry) {
        try {
            if (KIND_PRIVILEGED.equals(entry.kind)) {
                // Privileged permissions silently resolve to "not granted" unless the
                // app really is a priv-app; checkPermission tells us which.
                return PermissionUtils.checkPermission(context, entry.permission);
            }
            return PermissionUtils.checkPermission(context, entry.permission);
        } catch (Exception e) {
            return false;
        }
    }

    public static void onReceive(final Context context, Intent intent) {
        String subcommand = intent.getStringExtra("subcommand");
        if (subcommand == null) subcommand = "status";

        if ("request".equals(subcommand)) {
            request(context, intent);
        } else {
            returnStatus(context, intent);
        }
    }

    private static void returnStatus(Context context, final Intent intent) {
        ResultReturner.returnData(context, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                List<Entry> entries = catalog();
                List<String> missing = new ArrayList<>();
                List<String> missingGrantable = new ArrayList<>();
                List<String> missingPrivileged = new ArrayList<>();

                out.beginObject();
                out.name("permissions").beginArray();
                for (Entry entry : entries) {
                    boolean granted = isGranted(context, entry);
                    out.beginObject();
                    out.name("key").value(entry.key);
                    out.name("permission").value(entry.permission);
                    out.name("label").value(entry.label);
                    out.name("kind").value(entry.kind);
                    out.name("granted").value(granted);
                    out.name("how_to").value(entry.howTo);
                    out.endObject();
                    if (!granted) {
                        missing.add(entry.key);
                        if (KIND_PRIVILEGED.equals(entry.kind)) missingPrivileged.add(entry.key);
                        else missingGrantable.add(entry.key);
                    }
                }
                out.endArray();
                writeStringArray(out, "missing", missing);
                writeStringArray(out, "missing_grantable", missingGrantable);
                writeStringArray(out, "missing_privileged", missingPrivileged);
                out.name("all_granted").value(missingGrantable.isEmpty());
                out.endObject();
            }
        });
    }

    /**
     * Opens the in-app prompt for the missing permissions.  The prompt answers the
     * pending client request itself once the user is done, so this handler does not
     * return a response.
     */
    private static void request(Context context, Intent intent) {
        List<Entry> entries = catalog();
        List<String> requestedKeys = new ArrayList<>();

        String explicit = intent.getStringExtra("permissions");
        if (explicit != null && !explicit.trim().isEmpty()) {
            for (String key : explicit.split("[,\\s]+")) {
                if (!key.isEmpty()) requestedKeys.add(key);
            }
        } else {
            for (Entry entry : entries) {
                if (!isGranted(context, entry)) requestedKeys.add(entry.key);
            }
        }

        if (requestedKeys.isEmpty()) {
            ResultReturner.returnData(context, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("all_granted").value(true);
                    out.name("message").value("Every permission this command needs is already granted");
                    out.endObject();
                }
            });
            return;
        }

        Intent prompt = new Intent(context, PermissionPromptActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putStringArrayListExtra(PermissionPromptActivity.EXTRA_KEYS, new ArrayList<>(requestedKeys))
            .putExtra(PermissionPromptActivity.EXTRA_REASON, intent.getStringExtra("reason"));
        ResultReturner.copyIntentExtras(intent, prompt);
        context.startActivity(prompt);
    }

    /** Writes a JSON array of strings under the given name. */
    public static void writeStringArray(JsonWriter out, String name, List<String> values) throws Exception {
        out.name(name).beginArray();
        for (String value : values) out.value(value);
        out.endArray();
    }

    /**
     * Used by the other API classes: when a command fails because a permission is
     * missing, open the prompt so granting it is one tap, and describe what is
     * missing in the JSON that the caller already receives.
     */
    public static void promptFor(Context context, String... keys) {
        try {
            Intent prompt = new Intent(context, PermissionPromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putStringArrayListExtra(PermissionPromptActivity.EXTRA_KEYS, new ArrayList<>(java.util.Arrays.asList(keys)));
            context.startActivity(prompt);
        } catch (Exception ignored) {
            // The caller still gets the permission error in its JSON response.
        }
    }
}
