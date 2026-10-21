package com.termux.api.activities;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.JsonWriter;

import com.termux.api.apis.PermissionAPI;
import com.termux.api.util.ResultReturner;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.util.ArrayList;

/**
 * Grants permissions by handing the work to the system.
 *
 * Runtime permissions are requested through
 * {@link #requestPermissions(String[], int)}, so Android shows its own
 * Allow/Deny dialog.  "Special app access" permissions have no dialog at all,
 * so their dedicated settings page is opened instead.  Privileged permissions
 * are never requested here - the JSON response explains that they need a
 * priv-app whitelist.
 *
 * The theme is translucent, not {@code Theme.NoDisplay}: a NoDisplay activity
 * must finish before onResume() returns, which cancels the permission request
 * before its dialog can be answered.  Nothing is drawn either way.
 *
 * After the dialog is answered the activity runs the follow-up action the
 * caller queued - for example {@code BluetoothAdapter.ACTION_REQUEST_ENABLE} -
 * so "allow the permission" and "switch the radio on" happen in one go instead
 * of one command run each.
 *
 * There is deliberately no UI of our own: anything drawn here would only
 * duplicate what the platform already shows.
 */
public class TermuxApiPermissionActivity extends Activity {

    private static final String LOG_TAG = "TermuxApiPermissionActivity";

    private static final int REQUEST_CODE = 0;

    /** Intent extra containing the runtime permissions to request. */
    public static final String PERMISSIONS_EXTRA = TermuxConstants.TERMUX_API_PACKAGE_NAME + ".permission_extra";

    /** Intent extra containing a system settings action to open (special app access). */
    public static final String SETTINGS_ACTION_EXTRA = TermuxConstants.TERMUX_API_PACKAGE_NAME + ".settings_action_extra";

    /** Intent extra containing a system dialog to show, e.g. BluetoothAdapter.ACTION_REQUEST_ENABLE. */
    public static final String SYSTEM_ACTION_EXTRA = TermuxConstants.TERMUX_API_PACKAGE_NAME + ".system_action_extra";

    /** Guards against onResume() being re-entered while the dialog is open. */
    private boolean flowStarted;

    /** Set once the follow-up action has been launched, so returning from it does nothing. */
    private boolean flowFinished;

    /**
     * Whether a permission is actually usable right now.
     *
     * {@link PermissionUtils#checkPermission} only covers runtime permissions.
     * "Special app access" permissions are app-ops with their own accessors, so
     * asking checkSelfPermission about them always answers "denied" and would
     * make a working feature look broken.
     */
    public static boolean isGranted(Context context, String permission) {
        if (Manifest.permission.WRITE_SETTINGS.equals(permission))
            return Settings.System.canWrite(context);
        if (Manifest.permission.SYSTEM_ALERT_WINDOW.equals(permission))
            return Settings.canDrawOverlays(context);
        if (Manifest.permission.MANAGE_EXTERNAL_STORAGE.equals(permission))
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
        if (Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.equals(permission)) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        if ("android.permission.ACCESS_NOTIFICATION_POLICY".equals(permission)) {
            NotificationManager notifications =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return notifications != null && notifications.isNotificationPolicyAccessGranted();
        }
        return PermissionUtils.checkPermission(context, permission);
    }

    /** Shows a system dialog such as "turn on Bluetooth?" - no UI of our own. */
    public static void requestSystemAction(Context context, String action) {
        if (action == null) return;

        Intent request = new Intent(context, TermuxApiPermissionActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SYSTEM_ACTION_EXTRA, action);

        try {
            context.startActivity(request);
        } catch (Exception ignored) {
            // The caller's JSON already explains what to switch on.
        }
    }

    /**
     * Enters the permission flow for the given manifest permission names: the
     * system dialog when one exists, the matching settings page otherwise.
     */
    public static void openSystemUi(Context context, String... permissions) {
        ArrayList<String> runtime = new ArrayList<>();
        String settingsAction = null;

        for (String permission : permissions) {
            PermissionAPI.Entry entry = PermissionAPI.findByPermission(permission);
            if (entry != null && PermissionAPI.KIND_PRIVILEGED.equals(entry.kind)) continue;
            if (entry != null && PermissionAPI.KIND_SETTINGS.equals(entry.kind)) {
                if (settingsAction == null) settingsAction = entry.settingsAction;
                continue;
            }
            runtime.add(permission);
        }

        startFlow(context, runtime, settingsAction, null);
    }

    /** Asks Android to show its own permission dialog for these permissions. */
    public static void requestRuntime(Context context, String... permissions) {
        startFlow(context, arrayList(permissions), null, null);
    }

    /** Opens the system page that owns a "special app access" permission. */
    public static void openSettingsPage(Context context, String action) {
        startFlow(context, new ArrayList<String>(), action, null);
    }

    /**
     * Launches this activity with everything the flow needs: the permissions to
     * ask for, and the action to run once they are granted.
     */
    private static void startFlow(Context context, ArrayList<String> permissions,
                                  String settingsAction, String systemAction) {
        Intent request = new Intent(context, TermuxApiPermissionActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (permissions != null && !permissions.isEmpty())
            request.putStringArrayListExtra(PERMISSIONS_EXTRA, permissions);
        if (settingsAction != null) request.putExtra(SETTINGS_ACTION_EXTRA, settingsAction);
        if (systemAction != null) request.putExtra(SYSTEM_ACTION_EXTRA, systemAction);

        try {
            context.startActivity(request);
        } catch (Exception ignored) {
            // The caller's JSON already says what is missing.
        }
    }

    private static ArrayList<String> arrayList(String[] values) {
        ArrayList<String> list = new ArrayList<>(values.length);
        for (String value : values) list.add(value);
        return list;
    }

    /**
     * Return a permission-error JSON response for permissions that have no
     * runtime dialog, then enter the flow for whatever is still missing.
     */
    public static void returnPermissionError(Context context, Intent intent, String... permissions) {
        final String[] requested = permissions;
        ResultReturner.returnData(context, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                writePermissionError(out, requested, null);
            }
        });
        openSystemUi(context, permissions);
    }

    public static boolean checkAndRequestPermissions(Context context, Intent intent, String... permissions) {
        final ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (!isGranted(context, permission)) {
                permissionsToRequest.add(permission);
            }
        }

        if (permissionsToRequest.isEmpty()) {
            return true;
        }

        // A hardware dialog queued by the caller (turn Bluetooth on, open the
        // location switch) runs right after the permission dialog is answered,
        // so the user is not sent back to the shell between the two.
        final String systemAction = intent == null ? null : intent.getStringExtra(SYSTEM_ACTION_EXTRA);
        final String settingsAction = intent == null ? null : intent.getStringExtra(SETTINGS_ACTION_EXTRA);

        ResultReturner.returnData(context, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                writePermissionError(out, permissionsToRequest.toArray(new String[0]), systemAction);
            }
        });

        startFlow(context, permissionsToRequest, settingsAction, systemAction);
        return false;
    }

    private static void writePermissionError(JsonWriter out, String[] permissions,
                                             String followUpAction) throws Exception {
        String errorMessage = "Please grant the following permission"
                + (permissions.length > 1 ? "s" : "")
                + " to use this command: "
                + TextUtils.join(" ,", permissions);
        String hint = followUpAction == null
                ? "Ikuti layar Android yang muncul, lalu jalankan ulang perintahnya."
                : "Setujui dialog izin, lalu dialog berikutnya untuk menyalakan perangkatnya, lalu jalankan ulang perintahnya.";

        out.beginObject();
        out.name("error").value(errorMessage);
        out.name("hint").value(hint);
        out.name("needs_permission").beginArray();
        for (String permission : permissions) out.value(permission);
        out.endArray();
        out.endObject();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Logger.logDebug(LOG_TAG, "onNewIntent");

        super.onNewIntent(intent);
        setIntent(intent);
        flowStarted = false;
        flowFinished = false;
    }

    @Override
    protected void onResume() {
        Logger.logVerbose(LOG_TAG, "onResume");
        super.onResume();

        // Coming back from the dialog we just opened: nothing left to do.
        if (flowFinished || flowStarted) return;
        flowStarted = true;

        Intent intent = getIntent();
        ArrayList<String> requested = intent.getStringArrayListExtra(PERMISSIONS_EXTRA);
        ArrayList<String> missing = new ArrayList<>();
        if (requested != null) {
            for (String permission : requested) {
                if (!isGranted(this, permission)) missing.add(permission);
            }
        }

        if (missing.isEmpty()) {
            runFollowUpAction();
        } else {
            PermissionUtils.requestPermissions(this, missing.toArray(new String[0]), REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_CODE) return;

        flowStarted = false;
        boolean allGranted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) allGranted = false;
        }

        // Granted: keep going into the hardware dialog the caller queued.
        // Denied: stop, the JSON response already told the user what is missing.
        if (allGranted) runFollowUpAction();
        else finish();
    }

    /** Runs the system dialog / settings page the caller asked for, then exits. */
    private void runFollowUpAction() {
        if (flowFinished) return;
        flowFinished = true;

        String systemAction = getIntent().getStringExtra(SYSTEM_ACTION_EXTRA);
        if (systemAction != null) {
            finish();
            try {
                startActivity(new Intent(systemAction));
            } catch (Exception ignored) {
                // The JSON response already tells the user what to switch on.
            }
            return;
        }

        String settingsAction = getIntent().getStringExtra(SETTINGS_ACTION_EXTRA);
        if (settingsAction != null) {
            finish();
            openSettingsPageNow(settingsAction);
            return;
        }

        finish();
    }

    /**
     * Opens the settings page that owns a special permission.  Some actions
     * need the package URI to land on this app's row, others reject it, so
     * both forms are tried before giving up.
     */
    private void openSettingsPageNow(String action) {
        try {
            startActivity(new Intent(action, Uri.parse("package:" + getPackageName())));
        } catch (Exception withPackageUri) {
            try {
                startActivity(new Intent(action));
            } catch (Exception ignored) {
                // The JSON response already names the page to open.
            }
        }
    }

}
