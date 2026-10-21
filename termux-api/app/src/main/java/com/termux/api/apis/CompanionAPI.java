package com.termux.api.apis;

import android.bluetooth.BluetoothDevice;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.MacAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonWriter;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.util.List;

/**
 * Exposing {@link CompanionDeviceManager}.
 *
 * <p>Association is what makes a Bluetooth device "known" to the platform: after
 * it, the app may observe the device's presence, keep a connection alive without
 * location services, and skip the per-device pairing prompts that a plain
 * {@code BluetoothAdapter} flow goes through. The chooser that
 * {@code associate()} opens is the platform's own UI — there is no other way to
 * create an association, so {@code termux-companion associate} launches it and
 * reports what the user picked.
 */
public class CompanionAPI {

    private static final String LOG_TAG = "CompanionAPI";

    /** Every command here needs CompanionDeviceManager, i.e. Android 8.0 (API 26). */
    private static final int MIN_API = Build.VERSION_CODES.O;

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                String command = intent.getStringExtra("command");
                if (command == null || command.isEmpty()) command = "status";

                if (Build.VERSION.SDK_INT < MIN_API) {
                    error(out, "CompanionDeviceManager needs Android 8.0 (API 26)");
                    return;
                }

                CompanionDeviceManager manager =
                    (CompanionDeviceManager) context.getSystemService(Context.COMPANION_DEVICE_SERVICE);
                if (manager == null) {
                    error(out, "CompanionDeviceManager unavailable on this device");
                    return;
                }

                switch (command) {
                    case "list":
                        list(out, manager);
                        break;
                    case "status":
                        status(out, manager);
                        break;
                    case "observe":
                        observe(out, manager, intent, true);
                        break;
                    case "unobserve":
                        observe(out, manager, intent, false);
                        break;
                    case "disassociate":
                        disassociate(out, manager, intent);
                        break;
                    case "associate":
                        associate(context, intent);
                        break;
                    default:
                        error(out, "Unknown command: " + command);
                        break;
                }
            }
        });
    }

    private static void list(JsonWriter out, CompanionDeviceManager manager) throws Exception {
        List<AssociationInfo> associations = manager.getAssociations();

        out.beginObject();
        out.name("count").value(associations == null ? 0 : associations.size());
        out.name("associations");
        out.beginArray();
        if (associations != null) {
            for (AssociationInfo info : associations) {
                out.beginObject();
                out.name("id").value(info.getId());
                MacAddress mac = info.getDeviceMacAddress();
                if (mac != null) out.name("address").value(mac.toString());
                out.name("profile").value(info.getDeviceProfile());
                out.endObject();
            }
        }
        out.endArray();
        out.endObject();
    }

    private static void status(JsonWriter out, CompanionDeviceManager manager) throws Exception {
        List<AssociationInfo> associations = manager.getAssociations();
        out.beginObject();
        out.name("available").value(true);
        out.name("api_level").value(Build.VERSION.SDK_INT);
        out.name("count").value(associations == null ? 0 : associations.size());
        out.endObject();
    }

    private static void observe(JsonWriter out, CompanionDeviceManager manager, Intent intent,
                                boolean start) throws Exception {
        String address = intent.getStringExtra("address");
        if (address == null || address.isEmpty()) {
            error(out, "Missing 'address' (Bluetooth MAC of an associated device)");
            return;
        }
        try {
            if (start) {
                manager.startObservingDevicePresence(address);
            } else {
                manager.stopObservingDevicePresence(address);
            }
        } catch (Exception e) {
            // Not associated, malformed MAC, or observer limit reached - the
            // platform says which through the exception type.
            error(out, "CompanionDeviceManager refused (" + e.getClass().getSimpleName() + "): "
                + e.getMessage());
            return;
        }
        out.beginObject();
        out.name("address").value(address);
        out.name("observing").value(start);
        out.endObject();
    }

    private static void disassociate(JsonWriter out, CompanionDeviceManager manager, Intent intent)
        throws Exception {
        String address = intent.getStringExtra("address");
        boolean everything = address == null || address.isEmpty();

        int removed = 0;
        List<AssociationInfo> associations = manager.getAssociations();
        if (associations != null) {
            for (AssociationInfo info : associations) {
                MacAddress mac = info.getDeviceMacAddress();
                boolean match = everything || (mac != null && mac.toString().equalsIgnoreCase(address));
                if (!match) continue;
                manager.disassociate(info);
                removed++;
            }
        }

        List<AssociationInfo> left = manager.getAssociations();
        out.beginObject();
        out.name("disassociated").value(removed);
        out.name("remaining").value(left == null ? 0 : left.size());
        out.endObject();
    }

    private static void associate(Context context, Intent intent) {
        Intent activityIntent = new Intent(context, AssociateActivity.class);
        Bundle extras = intent.getExtras();
        if (extras != null) activityIntent.putExtras(extras);
        activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(activityIntent);
        // The chooser activity answers on the caller's socket, so nothing is
        // written here - writing a second response would confuse the client.
    }

    /**
     * Runs the platform association chooser and reports the picked device.
     *
     * <p>{@code CompanionDeviceManager.associate()} only hands back an
     * {@link IntentSender}; the actual choice is made in a system activity, which
     * is why this needs an activity of its own instead of a broadcast receiver.
     */
    public static class AssociateActivity extends AppCompatActivity {

        private static final String LOG_TAG = "CompanionAssociate";
        private static final int REQUEST_CHOOSER = 0x0CD;

        private boolean resultReturned = false;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            Logger.logDebug(LOG_TAG, "onCreate");
            super.onCreate(savedInstanceState);

            final Intent origin = getIntent();

            CompanionDeviceManager manager =
                (CompanionDeviceManager) getSystemService(Context.COMPANION_DEVICE_SERVICE);
            if (manager == null) {
                fail(origin, "CompanionDeviceManager unavailable on this device");
                return;
            }

            AssociationRequest request = new AssociationRequest.Builder()
                .addDeviceFilter(new BluetoothDeviceFilter.Builder().build())
                .setSingleDevice(true)
                .build();

            CompanionDeviceManager.Callback callback = new CompanionDeviceManager.Callback() {
                @Override
                public void onDeviceFound(IntentSender sender) {
                    try {
                        startIntentSenderForResult(sender, REQUEST_CHOOSER, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        fail(origin, "Association chooser could not be opened: " + e.getMessage());
                    }
                }

                @Override
                public void onFailure(CharSequence message) {
                    fail(origin, "Association failed: " + message);
                }
            };

            try {
                manager.associate(request, callback, new Handler(Looper.getMainLooper()));
            } catch (Exception e) {
                fail(origin, "CompanionDeviceManager refused (" + e.getClass().getSimpleName() + "): "
                    + e.getMessage());
            }
        }

        @Override
        protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != REQUEST_CHOOSER || resultReturned) return;

            if (resultCode != RESULT_OK || data == null) {
                fail(getIntent(), "Association cancelled");
                return;
            }

            BluetoothDevice device = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
            if (device == null) {
                fail(getIntent(), "The chooser returned no device");
                return;
            }

            String address;
            try {
                address = device.getAddress();
            } catch (SecurityException e) {
                fail(getIntent(), "Bluetooth address is not readable: " + e.getMessage());
                return;
            }

            resultReturned = true;
            final String picked = address;
            ResultReturner.returnData(this, getIntent(), new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("associated").value(true);
                    out.name("address").value(picked);
                    out.endObject();
                }
            });
            finish();
        }

        private void fail(Intent origin, final String message) {
            if (!resultReturned) {
                resultReturned = true;
                ResultReturner.returnData(this, origin, new ResultReturner.ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        out.beginObject().name("API_ERROR").value(message).endObject();
                    }
                });
            }
            finish();
        }
    }

    private static void error(JsonWriter out, String message) throws Exception {
        out.beginObject().name("API_ERROR").value(message).endObject();
    }
}
