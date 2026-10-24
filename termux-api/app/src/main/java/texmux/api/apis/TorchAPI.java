package texmux.api.apis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonWriter;

import texmux.api.TermuxApiReceiver;
import texmux.api.activities.TermuxApiPermissionActivity;
import texmux.api.util.ResultReturner;
import texmux.shared.logger.Logger;

/**
 * "Torch" API method: switches the camera flash LED on or off.
 *
 * <p>The framework gives no return value for {@code setTorchMode}, so success
 * and silent failure look identical to the caller. This handler therefore
 * listens to {@link CameraManager.TorchCallback} and reports the state the
 * system actually reached, plus an explicit error when there is no flash unit,
 * the camera is busy, or the state never changed. It never answers with an
 * empty body.</p>
 */
public class TorchAPI {

    private static final String LOG_TAG = "TorchAPI";

    /** How long the platform gets to deliver the state it already knows. */
    private static final long FIRST_REPORT_TIMEOUT_MS = 400L;

    /** How long the platform gets to report the state after setTorchMode(). */
    private static final long STATE_TIMEOUT_MS = 900L;

    /** Polling granularity while waiting for a callback to land. */
    private static final long POLL_INTERVAL_MS = 25L;

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        // Writes the needs_permission JSON itself when CAMERA was never granted.
        if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                Manifest.permission.CAMERA)) return;

        final boolean enabled = intent.getBooleanExtra("enabled", false);

        final TorchResult result = applyTorch(context, enabled);

        if (result.error != null) {
            Logger.logWarn(LOG_TAG, result.error);
        } else {
            Logger.logInfo(LOG_TAG, "Torch requested " + (enabled ? "on" : "off")
                + ", reported state " + result.state + " (verified=" + result.verified + ")");
        }

        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                if (result.error != null) {
                    out.name("error").value(result.error);
                    out.name("error_type").value(result.errorType);
                }
                out.name("requested").value(enabled);
                out.name("enabled");
                if (result.state != null) out.value(result.state.booleanValue()); else out.nullValue();
                out.name("verified").value(result.verified);
                if (result.cameraId != null) out.name("camera_id").value(result.cameraId);
                if (result.note != null) out.name("note").value(result.note);
                out.endObject();
            }
        });
    }

    /** Switches the torch and collects everything worth reporting about it. */
    private static TorchResult applyTorch(Context context, boolean enabled) {
        final TorchResult result = new TorchResult();

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            result.errorType = "NoCameraService";
            result.error = "The camera service is not available on this device, so the torch cannot be switched";
            return result;
        }

        try {
            result.cameraId = getTorchCameraId(cameraManager);
        } catch (CameraAccessException e) {
            fillAccessError(result, e);
            return result;
        }

        if (result.cameraId == null) {
            result.errorType = "NoFlashUnit";
            result.error = "No camera on this device reports a flash unit, so there is no torch to switch "
                + "(camera ids were enumerated and every one had FLASH_INFO_AVAILABLE == false)";
            return result;
        }

        TorchObserver observer = new TorchObserver(result.cameraId);
        try {
            // Registering also delivers the state the system currently has, so
            // "already on" is not mistaken for "the request did nothing".
            cameraManager.registerTorchCallback(observer, new Handler(Looper.getMainLooper()));
            observer.awaitFirstReport(FIRST_REPORT_TIMEOUT_MS);

            cameraManager.setTorchMode(result.cameraId, enabled);
            observer.awaitState(enabled, STATE_TIMEOUT_MS);
        } catch (CameraAccessException e) {
            fillAccessError(result, e);
            return result;
        } catch (IllegalArgumentException e) {
            result.errorType = "TorchNotSupported";
            result.error = "Camera " + result.cameraId + " refused torch control: " + e.getMessage()
                + " (the device can take photos with the flash but exposes no camera2 torch mode)";
            return result;
        } catch (SecurityException e) {
            result.errorType = "CameraPermissionDenied";
            result.error = "The CAMERA permission is not granted, so the torch cannot be controlled";
            return result;
        } catch (RuntimeException e) {
            result.errorType = e.getClass().getSimpleName();
            result.error = "Failed to switch the torch on camera " + result.cameraId + ": " + e.getMessage();
            return result;
        } finally {
            try {
                cameraManager.unregisterTorchCallback(observer);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to unregister torch callback", e);
            }
        }

        result.state = observer.lastState;

        if (observer.unavailable) {
            result.errorType = "TorchUnavailable";
            result.error = "The torch is unavailable on camera " + result.cameraId
                + ": the camera is in use (by this or another app) or the flash is disabled by the device policy";
            return result;
        }

        if (observer.lastState == null) {
            result.verified = false;
            result.note = "The system reported no torch state within " + STATE_TIMEOUT_MS
                + " ms; setTorchMode was accepted but the result could not be confirmed";
            return result;
        }

        result.verified = observer.lastState.booleanValue() == enabled;
        if (!result.verified) {
            result.errorType = "NotApplied";
            result.error = "The torch is still " + (observer.lastState.booleanValue() ? "on" : "off")
                + " on camera " + result.cameraId + " after requesting it " + (enabled ? "on" : "off");
        }

        return result;
    }

    /** Turns a CameraAccessException into a message that names the actual cause. */
    private static void fillAccessError(TorchResult result, CameraAccessException e) {
        result.errorType = "CameraAccessException";
        String cause;
        switch (e.getReason()) {
            case CameraAccessException.CAMERA_IN_USE:
                cause = "the camera is already in use by another app";
                break;
            case CameraAccessException.MAX_CAMERAS_IN_USE:
                cause = "too many cameras are already open";
                break;
            case CameraAccessException.CAMERA_DISABLED:
                cause = "the camera is disabled by the device policy";
                break;
            case CameraAccessException.CAMERA_DISCONNECTED:
                cause = "the camera was disconnected";
                break;
            case CameraAccessException.CAMERA_ERROR:
                cause = "the camera service reported an internal error";
                break;
            default:
                cause = "reason " + e.getReason() + ": " + e.getMessage();
                break;
        }
        result.error = "Cannot control the torch: " + cause;
    }

    /** Id of the first camera that has a flash unit, or null when none does. */
    private static String getTorchCameraId(CameraManager cameraManager) throws CameraAccessException {
        String[] cameraIdList = cameraManager.getCameraIdList();

        for (String id : cameraIdList) {
            Boolean flashAvailable = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flashAvailable != null && flashAvailable) {
                return id;
            }
        }
        return null;
    }

    /** Everything the handler reports about one torch request. */
    private static final class TorchResult {
        String error;
        String errorType;
        String cameraId;
        String note;
        Boolean state;
        boolean verified;
    }

    /**
     * Collects {@link CameraManager.TorchCallback} reports for one camera id.
     * Callbacks arrive on the main looper, the handler thread only polls the
     * volatile fields, so no locking is needed.
     */
    private static final class TorchObserver extends CameraManager.TorchCallback {
        private final String cameraId;

        volatile Boolean lastState;
        volatile boolean seenReport;
        volatile boolean unavailable;

        TorchObserver(String cameraId) {
            this.cameraId = cameraId;
        }

        @Override
        public void onTorchModeChanged(String id, boolean enabled) {
            if (!cameraId.equals(id)) return;
            lastState = enabled;
            unavailable = false;
            seenReport = true;
        }

        @Override
        public void onTorchModeUnavailable(String id) {
            if (!cameraId.equals(id)) return;
            unavailable = true;
            seenReport = true;
        }

        /** Waits until the platform reported anything about this camera. */
        void awaitFirstReport(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!seenReport && System.currentTimeMillis() < deadline) {
                sleep(POLL_INTERVAL_MS);
            }
        }

        /** Waits until the reported state matches, or the timeout expires. */
        void awaitState(boolean expected, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Boolean state = lastState;
                if (unavailable || (state != null && state.booleanValue() == expected)) return;
                sleep(POLL_INTERVAL_MS);
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
