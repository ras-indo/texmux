package com.termux.api.activities;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.JsonWriter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.api.apis.PermissionAPI;
import com.termux.api.util.ResultReturner;

import java.util.ArrayList;
import java.util.List;

/**
 * Visible in-app permission prompt.
 *
 * Android has three flavours of permission and only one of them has a popup:
 *
 *   runtime     -> the standard Allow/Deny dialog.  We trigger it directly, so
 *                  the user taps once and is done.
 *   settings    -> no popup exists at all.  We deep-link to the exact system
 *                  page for that permission instead of telling the user to go
 *                  hunting through Settings.
 *   privileged  -> can never be granted at runtime; explain why and offer the
 *                  priv-app recipe instead of a dead button.
 *
 * The activity answers the pending API request when it closes, so the command
 * that triggered it does not fail before the user has had a chance to tap.
 */
public class PermissionPromptActivity extends Activity {

    public static final String EXTRA_KEYS = "permission_keys";
    public static final String EXTRA_REASON = "permission_reason";

    private static final int REQUEST_RUNTIME = 1001;

    private final List<PermissionAPI.Entry> pending = new ArrayList<>();
    private LinearLayout rows;
    private TextView subtitle;
    private String reason;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ArrayList<String> keys = getIntent().getStringArrayListExtra(EXTRA_KEYS);
        reason = getIntent().getStringExtra(EXTRA_REASON);
        if (keys != null) {
            for (String key : keys) {
                PermissionAPI.Entry entry = PermissionAPI.findByKey(key);
                if (entry != null) pending.add(entry);
            }
        }

        setTitle("Izin dibutuhkan");
        setContentView(buildLayout());
        render();

        // Runtime-only request: jump straight to the system dialog, which is the
        // fastest path for the user.  The list stays visible underneath so they
        // can see what else (special/privileged) still needs attention.
        if (onlyRuntimeMissing()) requestRuntime();
    }

    private boolean onlyRuntimeMissing() {
        boolean anyRuntime = false;
        for (PermissionAPI.Entry entry : pending) {
            if (PermissionAPI.isGranted(this, entry)) continue;
            if (!PermissionAPI.KIND_RUNTIME.equals(entry.kind)) return false;
            anyRuntime = true;
        }
        return anyRuntime;
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Termux:API butuh izin berikut");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.START);
        root.addView(title);

        subtitle = new TextView(this);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        root.addView(subtitle);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        root.addView(rows);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(14), 0, 0);

        Button grantAll = new Button(this);
        grantAll.setText("Beri semua");
        grantAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRuntime();
            }
        });
        buttons.addView(grantAll);

        Button close = new Button(this);
        close.setText("Tutup");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        buttons.addView(close);

        root.addView(buttons);
        return scroll;
    }

    /** Redraws one row per requested permission with its live state. */
    private void render() {
        rows.removeAllViews();

        int missingGrantable = 0;
        int missingPrivileged = 0;
        for (PermissionAPI.Entry entry : pending) {
            if (PermissionAPI.isGranted(this, entry)) continue;
            if (PermissionAPI.KIND_PRIVILEGED.equals(entry.kind)) missingPrivileged++;
            else missingGrantable++;
        }

        StringBuilder summary = new StringBuilder();
        if (missingGrantable == 0 && missingPrivileged == 0) {
            summary.append("Semua izin sudah diberikan.");
        } else {
            if (missingGrantable > 0) summary.append(missingGrantable).append(" izin bisa diberikan sekarang.");
            if (missingPrivileged > 0) {
                if (summary.length() > 0) summary.append(" ");
                summary.append(missingPrivileged).append(" izin hanya bisa lewat priv-app (root).");
            }
        }
        if (reason != null && !reason.isEmpty()) summary.append("\n").append(reason);
        subtitle.setText(summary.toString());

        for (final PermissionAPI.Entry entry : pending) {
            rows.addView(buildRow(entry));
        }
    }

    private View buildRow(final PermissionAPI.Entry entry) {
        boolean granted = PermissionAPI.isGranted(this, entry);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView label = new TextView(this);
        label.setText((granted ? "\u2713  " : "\u2717  ") + entry.label);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(label);

        TextView detail = new TextView(this);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setPadding(0, dp(2), 0, dp(6));
        detail.setText(granted ? entry.permission : entry.howTo);
        row.addView(detail);

        if (!granted) {
            Button action = new Button(this);
            action.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (PermissionAPI.KIND_RUNTIME.equals(entry.kind)) {
                action.setText(isPermanentlyDenied(entry) ? "Buka pengaturan izin" : "Izinkan");
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (isPermanentlyDenied(entry)) openAppPermissionSettings();
                        else requestRuntime();
                    }
                });
            } else if (PermissionAPI.KIND_SETTINGS.equals(entry.kind)) {
                action.setText("Buka halaman pengaturan");
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openSettingsPage(entry);
                    }
                });
            } else {
                action.setText("Salin XML priv-app");
                action.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyPrivilegedRecipe(entry);
                    }
                });
            }
            row.addView(action);
        }

        return row;
    }

    /**
     * Android stops showing the dialog after the user denies twice.  In that state
     * requestPermissions() returns immediately and silently, so the only way to
     * grant is the app's own permission page - open it directly.
     */
    private boolean isPermanentlyDenied(PermissionAPI.Entry entry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if (PermissionAPI.isGranted(this, entry)) return false;
        return !shouldShowRequestPermissionRationale(entry.permission);
    }

    private void requestRuntime() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Below API 23 permissions are granted at install time; there is no
            // dialog to show and requestPermissions() does not exist.
            return;
        }
        List<String> toRequest = new ArrayList<>();
        boolean permanentlyDenied = false;

        for (PermissionAPI.Entry entry : pending) {
            if (!PermissionAPI.KIND_RUNTIME.equals(entry.kind)) continue;
            if (PermissionAPI.isGranted(this, entry)) continue;
            if (isPermanentlyDenied(entry)) {
                permanentlyDenied = true;
                continue;
            }
            toRequest.add(entry.permission);
        }

        if (!toRequest.isEmpty()) {
            requestPermissions(toRequest.toArray(new String[0]), REQUEST_RUNTIME);
        } else if (permanentlyDenied) {
            openAppPermissionSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RUNTIME) return;

        render();

        boolean anyDenied = false;
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) anyDenied = true;
        }
        // Denied outright == "Don't allow" twice or a policy block: the system
        // dialog will not appear again, so point at the settings page.
        if (anyDenied) {
            for (int i = 0; i < grantResults.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(permissions[i])) {
                    openAppPermissionSettings();
                    return;
                }
            }
        }
    }

    /** Opens the exact system page for a special-access permission. */
    private void openSettingsPage(PermissionAPI.Entry entry) {
        if (entry.settingsAction == null) {
            openAppPermissionSettings();
            return;
        }
        try {
            Intent intent = new Intent(entry.settingsAction);
            if (entry.settingsAction.equals(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    || entry.settingsAction.equals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    || entry.settingsAction.equals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)) {
                intent.setData(Uri.parse("package:" + getPackageName()));
            } else if (entry.settingsAction.equals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)) {
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        } catch (Exception e) {
            openAppPermissionSettings();
        }
    }

    private void openAppPermissionSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak bisa membuka pengaturan: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Privileged permissions cannot be granted by any dialog.  Put the whitelist
     * XML on the clipboard so the user (or their root script) can paste it.
     */
    private void copyPrivilegedRecipe(PermissionAPI.Entry entry) {
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<permissions>\n"
            + "    <privapp-permissions package=\"" + getPackageName() + "\">\n"
            + "        <permission name=\"" + entry.permission + "\"/>\n"
            + "    </privapp-permissions>\n"
            + "</permissions>\n";
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("privapp-permissions", xml));
        }
        Toast.makeText(this, "XML disalin. Lihat termux-permission status untuk langkah priv-app.",
            Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from a settings page: refresh so the row flips to granted.
        render();
    }

    @Override
    public void finish() {
        // Answer the pending client request (if this activity was started by one).
        try {
            final List<String> granted = new ArrayList<>();
            final List<String> missing = new ArrayList<>();
            final List<String> privileged = new ArrayList<>();
            for (PermissionAPI.Entry entry : pending) {
                if (PermissionAPI.isGranted(this, entry)) granted.add(entry.key);
                else if (PermissionAPI.KIND_PRIVILEGED.equals(entry.kind)) privileged.add(entry.key);
                else missing.add(entry.key);
            }
            ResultReturner.returnData(this, getIntent(), new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("granted").beginArray();
                    for (String key : granted) out.value(key);
                    out.endArray();
                    PermissionAPI.writeStringArray(out, "missing", missing);
                    PermissionAPI.writeStringArray(out, "missing_privileged", privileged);
                    out.name("all_granted").value(missing.isEmpty());
                    out.endObject();
                }
            });
        } catch (Exception ignored) {
            // No pending caller (e.g. opened from the launcher): nothing to answer.
        }
        super.finish();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
