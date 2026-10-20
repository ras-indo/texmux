package com.termux.api.activities;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.JsonWriter;

import com.termux.api.apis.PermissionAPI;
import com.termux.api.util.ResultReturner;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.util.ArrayList;

public class TermuxApiPermissionActivity extends Activity {

    private static final String LOG_TAG = "TermuxApiPermissionActivity";

    /**
     * Intent extra containing the permissions to request.
     */
    public static final String PERMISSIONS_EXTRA = TermuxConstants.TERMUX_API_PACKAGE_NAME + ".permission_extra";

    /**
     * Check for and request permissions if necessary.
     *
     * @return if all permissions were already granted
     */
    /**
     * Return a permission-error JSON response for a special permission that
     * cannot be requested via {@link Activity#requestPermissions(String[], int)}
     * (e.g. {@link Manifest.permission#WRITE_SETTINGS}).  Unlike
     * {@link #checkAndRequestPermissions(Context, Intent, String...)}, this does
     * NOT launch a permission-request activity — the caller is expected to
     * open the relevant system-settings page instead.
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
                out.name("prompt_opened").value(!keysFor(requested).isEmpty());
                out.name("hint").value("Izinkan lewat layar yang muncul, lalu jalankan ulang perintahnya.");
                out.name("needs_permission").beginArray();
                for (String permission : requested) out.value(permission);
                out.endArray();
                out.endObject();
            }
        });
        openPrompt(context, keysFor(permissions), null);
    }

    /** Maps manifest permission names to the prompt's catalog keys. */
    private static ArrayList<String> keysFor(String... permissions) {
        ArrayList<String> keys = new ArrayList<>();
        for (PermissionAPI.Entry entry : PermissionAPI.catalog()) {
            for (String permission : permissions) {
                if (entry.permission.equals(permission) && !keys.contains(entry.key)) keys.add(entry.key);
            }
        }
        return keys;
    }

    /**
     * Opens the visible prompt.  Extras are deliberately NOT copied: the caller has
     * already answered with an error JSON, so the prompt must not write a second
     * response to the same client request.
     */
    private static void openPrompt(Context context, ArrayList<String> keys, String reason) {
        if (keys.isEmpty()) return;
        Intent prompt = new Intent(context, PermissionPromptActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putStringArrayListExtra(PermissionPromptActivity.EXTRA_KEYS, keys)
                .putExtra(PermissionPromptActivity.EXTRA_REASON, reason);
        try {
            context.startActivity(prompt);
        } catch (Exception ignored) {
            // Nothing else to do: the JSON already tells the user what is missing.
        }
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
                    out.name("prompt_opened").value(true);
                    out.name("hint").value("Izinkan lewat layar yang muncul, lalu jalankan ulang perintahnya.");
                    out.name("needs_permission").beginArray();
                    for (String permission : permissionsToRequest) out.value(permission);
                    out.endArray();
                    out.endObject();
                }
            });

            // Visible prompt instead of the old Theme.NoDisplay activity: it shows
            // what is being asked for, triggers the system dialog, and deep-links
            // the settings page once the dialog has been permanently dismissed.
            openPrompt(context, keysFor(permissionsToRequest.toArray(new String[0])),
                "Izin ini dibutuhkan oleh perintah yang baru saja dijalankan.");
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
        ArrayList<String> permissionValues = getIntent().getStringArrayListExtra(PERMISSIONS_EXTRA);
        PermissionUtils.requestPermissions(this, permissionValues.toArray(new String[0]), 0);
        finish();
    }

}
