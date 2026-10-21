package com.termux.api.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
 * This activity is declared with {@code Theme.NoDisplay} and never draws
 * anything.  Runtime permissions are requested through
 * {@link #requestPermissions(String[], int)}, so Android shows its own
 * Allow/Deny dialog.  "Special app access" permissions have no dialog at all,
 * so their dedicated settings page is opened instead.  Privileged permissions
 * are never requested here - the JSON response explains that they need a
 * priv-app whitelist.
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

        // One thing at a time: the dialog first, the settings page on the next run.
        if (!runtime.isEmpty()) {
            requestRuntime(context, runtime.toArray(new String[0]));
        } else if (settingsAction != null) {
            openSettingsPage(context, settingsAction);
        }
    }

    /** Asks Android to show its own permission dialog for these permissions. */
    public static void requestRuntime(Context context, String... permissions) {
        if (permissions == null || permissions.length == 0) return;

        Intent request = new Intent(context, TermuxApiPermissionActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putStringArrayListExtra(PERMISSIONS_EXTRA, arrayList(permissions));

        try {
            context.startActivity(request);
        } catch (Exception ignored) {
            // The caller's JSON already says what is missing.
        }
    }

    /** Opens the system page that owns a "special app access" permission. */
    public static void openSettingsPage(Context context, String action) {
        if (action == null) return;

        Intent settings = new Intent(context, TermuxApiPermissionActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(SETTINGS_ACTION_EXTRA, action);

        try {
            context.startActivity(settings);
        } catch (Exception ignored) {
            // The caller's JSON already explains where the toggle lives.
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
                String errorMessage = "Please grant the following permission"
                        + (requested.length > 1 ? "s" : "")
                        + " to use this command: "
                        + TextUtils.join(" ,", requested);
                out.beginObject();
                out.name("error").value(errorMessage);
                out.name("hint").value("Ikuti layar Android yang muncul, lalu jalankan ulang perintahnya.");
                out.name("needs_permission").beginArray();
                for (String permission : requested) out.value(permission);
                out.endArray();
                out.endObject();
            }
        });
        openSystemUi(context, permissions);
    }

    public static boolean checkAndRequestPermissions(Context context, Intent intent, String... permissions) {
        final ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (!PermissionUtils.checkPermission(context, permission)) {
                permissionsToRequest.add(permission);
            }
        }

        if (permissionsToRequest.isEmpty()) {
            return true;
        } else {
            ResultReturner.returnData(context, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    String errorMessage = "Please grant the following permission"
                            + (permissionsToRequest.size() > 1 ? "s" : "")
                            + " to use this command: "
                            + TextUtils.join(" ,", permissionsToRequest);
                    out.beginObject();
                    out.name("error").value(errorMessage);
                    out.name("hint").value("Ikuti layar Android yang muncul, lalu jalankan ulang perintahnya.");
                    out.name("needs_permission").beginArray();
                    for (String permission : permissionsToRequest) out.value(permission);
                    out.endArray();
                    out.endObject();
                }
            });

            openSystemUi(context, permissionsToRequest.toArray(new String[0]));
            return false;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Logger.logDebug(LOG_TAG, "onNewIntent");

        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        Logger.logVerbose(LOG_TAG, "onResume");
        super.onResume();

        Intent intent = getIntent();

        String settingsAction = intent.getStringExtra(SETTINGS_ACTION_EXTRA);
        if (settingsAction != null) {
            openSettingsPageNow(settingsAction);
            finish();
            return;
        }

        ArrayList<String> permissions = intent.getStringArrayListExtra(PERMISSIONS_EXTRA);
        if (permissions != null && !permissions.isEmpty()) {
            PermissionUtils.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_CODE);
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
