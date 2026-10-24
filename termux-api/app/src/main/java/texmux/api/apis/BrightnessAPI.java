package texmux.api.apis;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.JsonWriter;

import texmux.api.TermuxApiReceiver;
import texmux.api.activities.TermuxApiPermissionActivity;
import texmux.api.util.ResultReturner;
import texmux.shared.logger.Logger;

/**
 * "Brightness" API method: sets the screen brightness and/or the automatic
 * brightness mode, then answers with the values that are actually in effect.
 *
 * <p>{@code WRITE_SETTINGS} is a "special app access" permission, not a runtime
 * one: Android has no Allow/Deny dialog for it and a write attempted without it
 * is dropped without any error. That combination used to make the command exit
 * with an empty body, indistinguishable from success. Every outcome here writes
 * a JSON object, and the not-granted case names the settings page that grants
 * it.</p>
 */
public class BrightnessAPI {

    private static final String LOG_TAG = "BrightnessAPI";

    /** Brightness range accepted by the framework and by the script. */
    private static final int MIN_BRIGHTNESS = 0;
    private static final int MAX_BRIGHTNESS = 255;

    private static final String WRITE_SETTINGS_HOW_TO =
        "WRITE_SETTINGS is a special app access permission, so Android never shows a dialog for it. "
            + "Open Settings > Apps > Termux:API > Modify system settings and enable it, then run the command again.";

    public static void onReceive(final TermuxApiReceiver receiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        // Defence in depth: the dispatcher checks this too, but a handler that
        // writes a system setting must never assume the grant and silently
        // drop the write instead of reporting why nothing happened.
        if (!canWriteSettings(context)) {
            Logger.logWarn(LOG_TAG, "WRITE_SETTINGS not granted, cannot change brightness");
            returnWriteSettingsError(receiver, context, intent);
            return;
        }

        final ContentResolver contentResolver = context.getContentResolver();

        // Only touch what the caller actually sent. The script passes just
        // "--ez auto true" for auto mode; treating the absent "... --ei
        // brightness 0" there as "set brightness to 0" dimmed the screen to
        // minimum every time auto brightness was enabled.
        final boolean modeRequested = intent.hasExtra("auto");
        final boolean auto = intent.getBooleanExtra("auto", false);

        final boolean brightnessRequested = intent.hasExtra("brightness");
        final int requestedBrightness = brightnessRequested
            ? clamp(intent.getIntExtra("brightness", MIN_BRIGHTNESS)) : -1;

        // The mode goes first: while auto brightness is active the framework
        // clamps or ignores a manual SCREEN_BRIGHTNESS value.
        boolean modeWritten = true;
        if (modeRequested) {
            modeWritten = Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                auto ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                     : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        }

        boolean brightnessWritten = true;
        if (brightnessRequested) {
            brightnessWritten = Settings.System.putInt(contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, requestedBrightness);
        }

        // Read back instead of trusting the putInt() result: on several devices
        // the write reports success and the framework then clamps or ignores it.
        final int mode = readInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        final int brightness = readInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1);
        final boolean autoNow = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;

        final String error;
        final String errorType;
        if (!modeWritten) {
            error = "The system rejected setting the brightness mode to \"" + (auto ? "auto" : "manual") + "\"";
            errorType = "ModeWriteRejected";
        } else if (modeRequested && autoNow != auto) {
            error = "The brightness mode is still \"" + (autoNow ? "auto" : "manual")
                + "\" after requesting \"" + (auto ? "auto" : "manual") + "\"";
            errorType = "ModeNotApplied";
        } else if (!brightnessWritten) {
            error = "The system rejected setting the brightness to " + requestedBrightness;
            errorType = "BrightnessWriteRejected";
        } else {
            error = null;
            errorType = null;
        }

        // A different value read back is not necessarily a failure: the
        // framework clamps to the panel minimum on many devices. Report it
        // instead of pretending the exact value was stored.
        final String storedNote;
        if (error == null && brightnessRequested && brightness >= 0 && brightness != requestedBrightness) {
            storedNote = "The system stored " + brightness + " instead of the requested " + requestedBrightness
                + " (the framework clamps to the panel minimum)";
        } else {
            storedNote = null;
        }

        final boolean applied = error == null && storedNote == null;

        if (error != null) Logger.logWarn(LOG_TAG, error);
        else if (storedNote != null) Logger.logWarn(LOG_TAG, storedNote);

        ResultReturner.returnData(receiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                if (error != null) {
                    out.name("error").value(error);
                    out.name("error_type").value(errorType);
                }
                if (storedNote != null) out.name("note").value(storedNote);
                out.name("brightness");
                if (brightness >= 0) out.value(brightness); else out.nullValue();
                out.name("mode").value(autoNow ? "auto" : "manual");
                if (brightnessRequested) out.name("requested_brightness").value(requestedBrightness);
                if (modeRequested) out.name("requested_mode").value(auto ? "auto" : "manual");
                out.name("applied").value(applied);
                out.endObject();
            }
        });
    }

    /** True when this app may write Settings.System values (special app access). */
    private static boolean canWriteSettings(Context context) {
        return Settings.System.canWrite(context);
    }

    private static int clamp(int brightness) {
        if (brightness < MIN_BRIGHTNESS) return MIN_BRIGHTNESS;
        if (brightness > MAX_BRIGHTNESS) return MAX_BRIGHTNESS;
        return brightness;
    }

    /** Reads a Settings.System int, falling back when the key does not exist. */
    private static int readInt(ContentResolver contentResolver, String name, int fallback) {
        try {
            return Settings.System.getInt(contentResolver, name);
        } catch (Settings.SettingNotFoundException e) {
            Logger.logWarn(LOG_TAG, "Settings.System key \"" + name + "\" is not present on this device");
            return fallback;
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read Settings.System key \"" + name + "\"", e);
            return fallback;
        }
    }

    /**
     * Reports the missing special app access and opens the exact settings page
     * that grants it - the same flow the rest of the API uses for special
     * permissions, which have no runtime dialog.
     */
    private static void returnWriteSettingsError(final TermuxApiReceiver receiver, final Context context,
                                                 final Intent intent) {
        ResultReturner.returnData(receiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                out.name("error").value("android.permission.WRITE_SETTINGS is not granted, so Android "
                    + "would silently ignore the brightness change");
                out.name("error_type").value("WriteSettingsNotGranted");
                out.name("special_access").value("WRITE_SETTINGS");
                out.name("settings_action").value(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                out.name("how_to").value(WRITE_SETTINGS_HOW_TO);
                out.name("applied").value(false);
                out.name("needs_permission").beginArray();
                out.value(Manifest.permission.WRITE_SETTINGS);
                out.endArray();
                out.endObject();
            }
        });

        // Sends the user straight to "Modify system settings" instead of
        // leaving them to find it in the settings app.
        TermuxApiPermissionActivity.openSettingsPage(context, Settings.ACTION_MANAGE_WRITE_SETTINGS);
    }
}
