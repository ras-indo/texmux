package com.termux.api.apis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.JsonWriter;
import android.util.SparseArray;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.activities.TermuxApiPermissionActivity;
import com.termux.api.util.ResultReturner;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class BluetoothAPI {

    private static final String LOG_TAG = "BluetoothAPI";

    /**
     * Request codes for permission activity.
     */
    public static final int BLUETOOTH_PERMISSION_REQUEST_CODE = 10001;

    /**
     * Timeout for GATT operations in milliseconds.
     */
    private static final long GATT_OPERATION_TIMEOUT_MS = 10000;

    /**
     * Map of address -> GattContainer for tracking active GATT connections.
     */
    private static final ConcurrentHashMap<String, BluetoothGattContainer> gattClients = new ConcurrentHashMap<>();

    /**
     * Singleton GATT server instance.
     */
    private static BluetoothGattServer gattServerInstance = null;
    private static BluetoothLeAdvertiser advertiserInstance = null;
    private static AdvertiseCallback activeAdvertiseCallback = null;
    private static String advertiseName = null;
    private static String advertiseServiceUuid = null;

    /**
     * Active BLE scanner callback reference (for stop).
     */
    private static ScanCallback activeScanCallback = null;

    /**
     * Active BLE scan output writer thread.
     */
    private static BleScanWriter activeScanWriter = null;

    /**
     * RFCOMM server socket (kept open for incoming connections).
     */
    private static BluetoothServerSocket rfcommServerSocket = null;

    /**
     * Container for a single GATT client connection and its pending-operation semaphore.
     */
    static class BluetoothGattContainer {
        final Semaphore opSemaphore = new Semaphore(0);
        volatile int connectionState = BluetoothGatt.STATE_DISCONNECTED;
        BluetoothGatt gatt;

        BluetoothGattContainer(BluetoothGatt gatt) {
            this.gatt = gatt;
        }
    }

    // ============================================================
    // Entry Point
    // ============================================================

    /**
     * Entry point: dispatches to sub-handlers based on the "command" extra.
     */
    public static void onReceive(TermuxApiReceiver apiReceiver, Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        String command = intent.getStringExtra("command");
        if (command == null) {
            reply(apiReceiver, intent, out -> {
                out.append("{\"error\":\"Missing 'command' extra\"}\n");
            });
            return;
        }

        // Status-only reads must not nag. Everything else needs the adapter on,
        // so queue Android's "turn on Bluetooth?" dialog now: it runs right
        // after the permission dialog is answered instead of one command run
        // later.
        String adapterAction = intent.getStringExtra("action");
        boolean reportOnly = "adapter".equals(command)
                && (adapterAction == null || "info".equals(adapterAction));
        if (!reportOnly) {
            intent.putExtra(TermuxApiPermissionActivity.SYSTEM_ACTION_EXTRA,
                    BluetoothAdapter.ACTION_REQUEST_ENABLE);
        }

        // Check runtime permissions for API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Ask for exactly the permissions this command needs, and let
            // checkAndRequestPermissions() write the JSON answer when one is
            // missing.
            //
            // This used to test the command's own requirement but then request a
            // hardcoded CONNECT+SCAN set. For `advertise` (BLUETOOTH_ADVERTISE,
            // see requiredPermissions()) the whole requested set was already
            // granted, so checkAndRequestPermissions() returned true WITHOUT
            // writing anything, the `return` below still fired, and the client
            // sat out its 30 s timeout: "Handler did not produce a response".
            // BLUETOOTH_ADVERTISE lives in the same "Nearby devices" group as
            // SCAN/CONNECT but is granted per-permission, and nothing else in
            // this API ever requested it, so `advertise` could never succeed on
            // Android 12+ (and `gatt-server` could never advertise either).
            if (!TermuxApiPermissionActivity.checkAndRequestPermissions(
                    context, intent, requiredPermissions(command))) {
                return;
            }
        } else {
            if (!PermissionUtils.checkPermission(context, Manifest.permission.BLUETOOTH)) {
                TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                        Manifest.permission.BLUETOOTH);
                return;
            }
            if (!PermissionUtils.checkPermission(context, Manifest.permission.BLUETOOTH_ADMIN)) {
                TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                        Manifest.permission.BLUETOOTH_ADMIN);
                return;
            }
        }

        // Android owns the "turn on Bluetooth?" dialog. When the adapter is off,
        // ask the system for that dialog instead of failing with a bare error -
        // granting BLUETOOTH_SCAN/CONNECT alone can never make a scan work.
        BluetoothAdapter adapter = getBluetoothAdapter(context);
        if (adapter != null && !adapter.isEnabled() && !reportOnly) {
            reply(apiReceiver, intent, out -> {
                out.append("{\"error\":\"Bluetooth is off\",\"enabled\":false,")
                    .append("\"hint\":\"Setujui dialog Android untuk menyalakan Bluetooth, lalu jalankan ulang perintahnya.\"}\n");
            });
            TermuxApiPermissionActivity.requestSystemAction(
                    context, BluetoothAdapter.ACTION_REQUEST_ENABLE);
            return;
        }

        // Delegate to service for background work
        Intent serviceIntent = new Intent(context, BluetoothService.class);
        serviceIntent.setAction(intent.getAction());
        Bundle extras = intent.getExtras();
        if (extras != null) serviceIntent.putExtras(extras);
        try {
            context.startService(serviceIntent);
        } catch (Exception e) {
            // Background service starts are refused on O+ while the app is in
            // the background; without this the caller waited the full 30 s and
            // got "Handler did not produce a response" instead of the reason.
            Logger.logStackTraceWithMessage(LOG_TAG, "startService failed", e);
            returnError(apiReceiver, intent, "Bluetooth service could not start: " + e.getMessage());
        }
    }

    /**
     * The runtime permissions a command cannot run without, on API 31+.
     *
     * <p>Every list also contains BLUETOOTH_CONNECT: the handlers read adapter
     * and device names, which the platform gates on CONNECT even when the
     * command is really about scanning or advertising.</p>
     */
    public static String[] requiredPermissions(String command) {
        if (command == null) return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        switch (command) {
            case "le-scan":
                // ACCESS_FINE_LOCATION is part of this set because BLUETOOTH_SCAN
                // is declared without usesPermissionFlags="neverForLocation"
                // (AndroidManifest.xml:63): without a location grant the platform
                // lets the scan start and then silently drops every result, which
                // reads as "no devices nearby" instead of "permission missing".
                return new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION};
            case "advertise":
            case "gatt-server":
                return new String[]{
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                        Manifest.permission.BLUETOOTH_CONNECT};
            default:
                return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        }
    }

    /**
     * Get the BluetoothAdapter (may be null if no BT hardware).
     */
    private static BluetoothAdapter getBluetoothAdapter(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) return null;
        return manager.getAdapter();
    }

    /**
     * Why a BLE scan can come back empty through no fault of the caller's, or null when
     * empty simply means "nothing is advertising around here".
     */
    private static String bleEmptyReason(Context context, BluetoothAdapter adapter) {
        if (adapter == null || !adapter.isEnabled()) {
            return "Bluetooth turned off during the scan";
        }
        // Up to Android 11 the platform drops scan results unless location services are on.
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            boolean locationOn;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationOn = lm.isLocationEnabled();
            } else {
                locationOn = lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
            }
            if (!locationOn) {
                return "Location needs to be enabled - BLE scans return nothing without it";
            }
        } catch (Exception ignored) {
            // Provider queries can throw on odd ROMs; an unexplained empty list is still
            // better than dropping the answer.
        }
        return null;
    }

    /**
     * Return an error JSON with the given message.
     *
     * <p>Goes through {@link #reply} rather than calling ResultReturner itself,
     * so a failing write cannot turn an error into silence.</p>
     */
    private static void returnError(Object receiver, Intent intent, String message) {
        final String text = message == null ? "Unknown error" : message;
        Logger.logDebug(LOG_TAG, "Returning error: " + text);
        reply(receiver, intent, out -> {
            JSONObject obj = new JSONObject();
            obj.put("error", text);
            out.append(obj.toString()).append("\n");
        });
    }

    /**
     * Send a reply through {@link ResultReturner}, but never let a failure
     * inside the reply itself turn into silence.
     *
     * <p>{@code ResultReturner} runs the writer on its own thread and catches
     * whatever it throws (ResultReturner.java:325): the socket is then closed
     * without a single byte, SocketListener never sees a response, and the
     * caller is told "Handler did not produce a response" 30 s later. Any
     * writer body that touches the platform can throw - a SecurityException
     * from a getter that needs BLUETOOTH_CONNECT, a JSONException, an
     * IllegalStateException from a dead binder - so every reply is buffered
     * first and an error JSON is written in its place when it fails.</p>
     *
     * <p>Writers that take over the socket themselves ({@link
     * ResultReturner.BinaryOutput}, {@link ResultReturner.WithInput}, {@link
     * ResultReturner.WithAncillaryFd}) are passed through untouched: wrapping
     * them in a lambda would hide their type from ResultReturner's
     * {@code instanceof} checks and break binary/input/FD replies.</p>
     */
    private static void reply(Object receiver, Intent intent,
                              ResultReturner.ResultWriter body) {
        if (body instanceof ResultReturner.BinaryOutput
                || body instanceof ResultReturner.WithInput
                || body instanceof ResultReturner.WithAncillaryFd) {
            ResultReturner.returnData(receiver, intent, body);
            return;
        }
        ResultReturner.returnData(receiver, intent, out -> {
            StringWriter buffer = new StringWriter();
            PrintWriter bufferWriter = new PrintWriter(buffer);
            try {
                body.writeResult(bufferWriter);
                bufferWriter.flush();
                out.append(buffer.toString());
                out.flush();
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Bluetooth reply failed", t);
                try {
                    JSONObject err = new JSONObject();
                    err.put("error", "Reply failed: " + t.getClass().getSimpleName()
                            + (t.getMessage() != null ? ": " + t.getMessage() : ""));
                    out.append(err.toString()).append("\n");
                    out.flush();
                } catch (Throwable ignored) {
                    // Nothing left to report to.
                }
            }
        });
    }

    // ============================================================
    // Utility Methods
    // ============================================================

    private static String scanModeToString(int mode) {
        switch (mode) {
            case BluetoothAdapter.SCAN_MODE_NONE: return "none";
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE: return "connectable";
            case BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE: return "discoverable";
            default: return "unknown(" + mode + ")";
        }
    }

    private static String adapterStateToString(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF: return "off";
            case BluetoothAdapter.STATE_TURNING_ON: return "turning_on";
            case BluetoothAdapter.STATE_ON: return "on";
            case BluetoothAdapter.STATE_TURNING_OFF: return "turning_off";
            default: return "unknown(" + state + ")";
        }
    }

    private static String bondStateToString(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE: return "none";
            case BluetoothDevice.BOND_BONDING: return "bonding";
            case BluetoothDevice.BOND_BONDED: return "bonded";
            default: return "unknown(" + state + ")";
        }
    }

    private static String deviceTypeToString(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "classic";
            case BluetoothDevice.DEVICE_TYPE_LE: return "le";
            case BluetoothDevice.DEVICE_TYPE_DUAL: return "dual";
            case BluetoothDevice.DEVICE_TYPE_UNKNOWN: return "unknown";
            default: return "unknown(" + type + ")";
        }
    }

    /**
     * Read a no-argument getter reflectively.
     *
     * Several BluetoothAdapter/BluetoothDevice getters (getUuids,
     * getBluetoothClass) are not exposed by the public SDK stubs even though the
     * framework implements them, so calling them directly does not compile.
     * Reflection keeps this building and still reports real values on a device;
     * it returns null when the method is genuinely missing.
     */
    private static Object readProperty(Object target, String getter) {
        if (target == null) return null;
        try {
            java.lang.reflect.Method method = target.getClass().getMethod(getter);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Minimal JSON string escaping. */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Convert hex string to byte array.
     */
    private static byte[] hexStringToBytes(String hex) {
        if (hex == null) return new byte[0];
        hex = hex.replaceAll("\\s", "");
        int len = hex.length();
        if (len % 2 != 0) {
            hex = "0" + hex;
            len = hex.length();
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * Convert byte array to hex string.
     */
    private static String bytesToHexString(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    // ============================================================
    // BLE Scan Filter & Settings Builders
    // ============================================================

    /**
     * Build a list of ScanFilters from intent extras.
     * Supported extras:
     *   service_uuid  - String UUID to filter by
     *   device_name   - String exact device name filter
     *   device_address - String MAC address filter
     *   manufacturer_id - int manufacturer ID filter
     *   manufacturer_data - byte[] (hex string) manufacturer data
     */
    private static List<ScanFilter> buildScanFilters(Intent intent) {
        List<ScanFilter> filters = new ArrayList<>();
        ScanFilter.Builder builder = new ScanFilter.Builder();

        boolean hasFilter = false;

        String serviceUuidStr = intent.getStringExtra("service_uuid");
        if (serviceUuidStr != null && !serviceUuidStr.isEmpty()) {
            try {
                builder.setServiceUuid(ParcelUuid.fromString(serviceUuidStr));
                hasFilter = true;
            } catch (IllegalArgumentException e) {
                // Report instead of ignoring: dropping the filter turns a typo
                // into "the neighbourhood" instead of "that device".
                throw new IllegalArgumentException("Invalid service_uuid: " + serviceUuidStr);
            }
        }

        String deviceName = intent.getStringExtra("device_name");
        if (deviceName != null && !deviceName.isEmpty()) {
            builder.setDeviceName(deviceName);
            hasFilter = true;
        }

        String deviceAddress = intent.getStringExtra("device_address");
        if (deviceAddress != null && !deviceAddress.isEmpty()) {
            builder.setDeviceAddress(deviceAddress);
            hasFilter = true;
        }

        int manufacturerId = intent.getIntExtra("manufacturer_id", -1);
        if (manufacturerId >= 0) {
            String manufacturerDataHex = intent.getStringExtra("manufacturer_data");
            if (manufacturerDataHex != null && !manufacturerDataHex.isEmpty()) {
                byte[] data = hexStringToBytes(manufacturerDataHex);
                builder.setManufacturerData(manufacturerId, data);
            } else {
                builder.setManufacturerData(manufacturerId, new byte[0]);
            }
            hasFilter = true;
        }

        if (hasFilter) {
            filters.add(builder.build());
        }
        // If no filters, return empty list (no filter = all devices)
        return filters;
    }

    /**
     * Build ScanSettings from intent extras.
     *   scan_mode - String: "low_latency" (default), "low_power", "balanced"
     */
    private static ScanSettings buildScanSettings(Intent intent) {
        ScanSettings.Builder builder = new ScanSettings.Builder();

        String modeStr = intent.getStringExtra("scan_mode");
        int scanMode = ScanSettings.SCAN_MODE_LOW_LATENCY;
        if ("low_power".equals(modeStr)) {
            scanMode = ScanSettings.SCAN_MODE_LOW_POWER;
        } else if ("balanced".equals(modeStr)) {
            scanMode = ScanSettings.SCAN_MODE_BALANCED;
        }
        builder.setScanMode(scanMode);

        return builder.build();
    }

    // ============================================================
    // BluetoothService (background execution)
    // ============================================================

    public static class BluetoothService extends Service {

        private static final String LOG_TAG = "BluetoothService";

        /**
         * Set while a handler is still working on a reply from a worker thread.
         * onStartCommand() stops the service as soon as it returns, so without
         * this the component could be torn down while a scan is still collecting.
         */
        private volatile boolean keepAlive;

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Logger.logDebug(LOG_TAG, "onStartCommand");

            if (intent == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }

            String command = intent.getStringExtra("command");
            if (command == null) {
                Logger.logError(LOG_TAG, "No command extra");
                returnError(this, intent, "Missing 'command' extra");
                stopSelf(startId);
                return START_NOT_STICKY;
            }

            try {
                switch (command) {
                    case "adapter":
                        handleAdapter(intent);
                        break;
                    case "discovery":
                        handleDiscovery(intent);
                        break;
                    case "le-scan":
                        handleLeScan(intent);
                        break;
                    case "gatt-connect":
                        handleGattConnect(intent);
                        break;
                    case "gatt-discover":
                        handleGattDiscover(intent);
                        break;
                    case "gatt-read":
                        handleGattRead(intent);
                        break;
                    case "gatt-write":
                        handleGattWrite(intent);
                        break;
                    case "gatt-notify":
                        handleGattNotify(intent);
                        break;
                    case "gatt-rssi":
                        handleGattRssi(intent);
                        break;
                    case "battery":
                        handleBatteryLevel(intent);
                        break;
                    case "bond":
                        handleBond(intent);
                        break;
                    case "gatt-server":
                        handleGattServer(intent);
                        break;
                    case "advertise":
                        handleAdvertise(intent);
                        break;
                    case "rfcomm":
                        handleRfcomm(intent);
                        break;
                    case "profiles":
                        handleProfiles(intent);
                        break;
                    default:
                        Logger.logError(LOG_TAG, "Unknown command: " + command);
                        returnError(this, intent, "Unknown command: " + command);
                        break;
                }
            } catch (Throwable e) {
                // Throwable, not Exception: a NoClassDefFoundError on an old
                // ROM used to escape this catch, the handler thread died and the
                // client was left with silence instead of the reason.
                Logger.logStackTraceWithMessage(LOG_TAG, "Error handling command: " + command, e);
                returnError(this, intent, "Internal error: " + e);
            }

            if (!keepAlive) stopSelf(startId);
            return START_NOT_STICKY;
        }

        @Override
        public void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");
            super.onDestroy();
        }

        // ============================================================
        // Adapter Handler
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleAdapter(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported on this device");
                return;
            }

            String action = intent.getStringExtra("action");
            if (action == null) action = "info";

            switch (action) {
                case "enable":
                    adapter.enable();
                    reply(this, intent, out -> {
                        out.append("{\"status\":\"enabled\"}\n");
                    });
                    break;
                case "disable":
                    adapter.disable();
                    reply(this, intent, out -> {
                        out.append("{\"status\":\"disabled\"}\n");
                    });
                    break;
                case "set-name": {
                    String name = intent.getStringExtra("name");
                    if (name == null) {
                        returnError(this, intent, "Missing 'name' extra for set-name");
                        return;
                    }
                    adapter.setName(name);
                    reply(this, intent, out -> {
                        out.append("{\"status\":\"name_set\",\"name\":\"" + escapeJson(name) + "\"}\n");
                    });
                    break;
                }
                case "info":
                default:
                    reply(this, intent, new ResultReturner.ResultJsonWriter() {
                        @Override
                        @SuppressLint("HardwareIds")
                        public void writeJson(JsonWriter out) throws Exception {
                            out.beginObject();
                            out.name("enabled").value(adapter.isEnabled());
                            out.name("address").value(adapter.getAddress());
                            out.name("name").value(adapter.getName());
                            out.name("scan_mode").value(scanModeToString(adapter.getScanMode()));
                            out.name("state").value(adapterStateToString(adapter.getState()));
                            out.name("is_discovering").value(adapter.isDiscovering());
                            // Adapter-level capabilities and SDP profile UUIDs: the
                            // phone answered "which profiles does this dongle offer"
                            // with silence until now, so scripts had to guess.
                            // getUuids()/getBluetoothClass() are not in the public
                            // SDK stubs, so they are read reflectively.
                            out.name("le_supported").value(
                                getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
                            Object adapterUuids = readProperty(adapter, "getUuids");
                            out.name("uuids");
                            out.beginArray();
                            if (adapterUuids instanceof Object[]) {
                                for (Object uuid : (Object[]) adapterUuids) out.value(String.valueOf(uuid));
                            }
                            out.endArray();
                            Object adapterClass = readProperty(adapter, "getBluetoothClass");
                            if (adapterClass != null) {
                                Object deviceClass = readProperty(adapterClass, "getDeviceClass");
                                if (deviceClass instanceof Integer) {
                                    out.name("device_class").value((Integer) deviceClass);
                                }
                            }
                            out.name("bonded_devices").value(adapter.getBondedDevices().size());

                            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                            out.name("devices");
                            out.beginArray();
                            for (BluetoothDevice device : bonded) {
                                out.beginObject();
                                out.name("address").value(device.getAddress());
                                out.name("name").value(device.getName() != null ? device.getName() : "");
                                out.name("bond_state").value(bondStateToString(device.getBondState()));
                                out.name("type").value(deviceTypeToString(device.getType()));
                                Object deviceUuids = readProperty(device, "getUuids");
                                out.name("uuids");
                                out.beginArray();
                                if (deviceUuids instanceof Object[]) {
                                    for (Object uuid : (Object[]) deviceUuids) out.value(String.valueOf(uuid));
                                }
                                out.endArray();
                                out.endObject();
                            }
                            out.endArray();
                            out.endObject();
                        }
                    });
                    break;
            }
        }

        // ============================================================
        // Discovery Handler
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleDiscovery(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }

            String action = intent.getStringExtra("action");
            if (action == null) action = "status";

            switch (action) {
                case "start":
                    adapter.startDiscovery();
                    reply(this, intent, out -> {
                        out.append("{\"status\":\"discovery_started\"}\n");
                    });
                    break;
                case "cancel":
                    adapter.cancelDiscovery();
                    reply(this, intent, out -> {
                        out.append("{\"status\":\"discovery_cancelled\"}\n");
                    });
                    break;
                case "bonded":
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    JSONArray arr = new JSONArray();
                    for (BluetoothDevice device : bonded) {
                        try {
                            JSONObject obj = new JSONObject();
                            obj.put("address", device.getAddress());
                            obj.put("name", device.getName() != null ? device.getName() : "");
                            obj.put("bond_state", bondStateToString(device.getBondState()));
                            obj.put("type", deviceTypeToString(device.getType()));
                            ParcelUuid[] uuids = device.getUuids();
                            if (uuids != null) {
                                JSONArray uuidArr = new JSONArray();
                                for (ParcelUuid pu : uuids) {
                                    uuidArr.put(pu.toString());
                                }
                                obj.put("uuids", uuidArr);
                            } else {
                                obj.put("uuids", new JSONArray());
                            }
                            arr.put(obj);
                        } catch (Exception e) {
                            Logger.logError(LOG_TAG, "Error serializing bonded device: " + e.getMessage());
                        }
                    }
                    JSONObject result = new JSONObject();
                    try {
                        result.put("status", "bonded_list");
                        result.put("devices", arr);
                    } catch (Exception e) {
                        // ignore
                    }
                    String jsonStr = result.toString();
                    reply(this, intent, out -> {
                        out.append(jsonStr).append("\n");
                    });
                    break;
                case "status":
                default:
                    reply(this, intent, new ResultReturner.ResultJsonWriter() {
                        @Override
                        public void writeJson(JsonWriter out) throws Exception {
                            out.beginObject();
                            out.name("is_discovering").value(adapter.isDiscovering());
                            out.name("scan_mode").value(scanModeToString(adapter.getScanMode()));
                            out.endObject();
                        }
                    });
                    break;
            }
        }

        // ============================================================
        // Profiles Handler (A2DP, Headset)
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleProfiles(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }

            // getProfileProxy() waits for the ServiceListener, and that listener is
            // delivered on the main looper. Building this answer on the main thread
            // therefore blocked the only thread able to deliver the proxy, so both
            // lookups always timed out to null after 5 s of frozen UI. Collect on a
            // worker thread, where the wait can actually be satisfied.
            keepAlive = true;
            new Thread(() -> {
                try {
                    reply(this, intent, new ResultReturner.ResultJsonWriter() {
                        @Override
                        public void writeJson(JsonWriter out) throws Exception {
                            out.beginObject();

                            // A2DP sink (connected speakers/headphones)
                            out.name("a2dp");
                            BluetoothProfile a2dpProfile = getProfileProxy(BluetoothProfile.A2DP);
                            if (a2dpProfile != null) {
                                out.beginArray();
                                List<BluetoothDevice> a2dpDevices = a2dpProfile.getConnectedDevices();
                                for (BluetoothDevice dev : a2dpDevices) {
                                    writeDeviceSummary(out, dev);
                                }
                                out.endArray();
                            } else {
                                out.nullValue();
                            }

                            // Headset / Hands-Free
                            out.name("headset");
                            BluetoothProfile headsetProfile = getProfileProxy(BluetoothProfile.HEADSET);
                            if (headsetProfile != null) {
                                out.beginArray();
                                List<BluetoothDevice> hsDevices = headsetProfile.getConnectedDevices();
                                for (BluetoothDevice dev : hsDevices) {
                                    writeDeviceSummary(out, dev);
                                }
                                out.endArray();
                            } else {
                                out.nullValue();
                            }

                            out.endObject();
                        }
                    });
                } catch (Throwable t) {
                    returnError(this, intent, "Profile query failed: " + t);
                } finally {
                    keepAlive = false;
                    stopSelf();
                }
            }, "bt-profiles").start();
        }

        @SuppressLint("MissingPermission")
        private BluetoothProfile getProfileProxy(final int profileId) {
            final Object[] lock = new Object[1];
            final BluetoothProfile[] result = {null};
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) return null;

            synchronized (lock) {
                adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int id, BluetoothProfile profile) {
                        synchronized (lock) {
                            result[0] = profile;
                            lock.notifyAll();
                        }
                    }

                    @Override
                    public void onServiceDisconnected(int id) {
                        // no-op
                    }
                }, profileId);

                try {
                    lock.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result[0];
        }

        private void writeDeviceSummary(JsonWriter out, BluetoothDevice dev) throws Exception {
            out.beginObject();
            out.name("address").value(dev.getAddress());
            out.name("name").value(dev.getName() != null ? dev.getName() : "");
            out.name("bond_state").value(bondStateToString(dev.getBondState()));
            out.name("type").value(deviceTypeToString(dev.getType()));
            out.endObject();
        }

        // ============================================================
        // BLE Scan Handler
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleLeScan(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }

            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                returnError(this, intent, "BLE scanner not available");
                return;
            }

            String action = intent.getStringExtra("action");
            if (action == null) action = "start";

            if ("stop".equals(action)) {
                boolean wasRunning = activeScanCallback != null;
                if (activeScanCallback != null) {
                    try {
                        scanner.stopScan(activeScanCallback);
                    } catch (Exception e) {
                        // A vanished adapter throws here; the references are
                        // dropped either way, so answer instead of dying.
                        Logger.logStackTraceWithMessage(LOG_TAG, "stopScan failed", e);
                    }
                    activeScanCallback = null;
                }
                if (activeScanWriter != null) {
                    activeScanWriter.interrupt();
                    activeScanWriter = null;
                }
                reply(this, intent, out -> {
                    JSONObject obj = new JSONObject();
                    obj.put("status", "scan_stopped");
                    obj.put("was_running", wasRunning);
                    out.append(obj.toString()).append("\n");
                });
                return;
            }

            if ("status".equals(action)) {
                // `le-scan status` used to fall through into the start branch and
                // launch a five second scan just to ask whether one was running.
                reply(this, intent, out -> {
                    JSONObject obj = new JSONObject();
                    obj.put("running", activeScanCallback != null);
                    obj.put("streaming", activeScanWriter != null);
                    out.append(obj.toString()).append("\n");
                });
                return;
            }
            if (!"start".equals(action)) {
                returnError(this, intent, "Unknown le-scan action: " + action);
                return;
            }

            List<ScanFilter> filters;
            ScanSettings settings;
            try {
                filters = buildScanFilters(intent);
                settings = buildScanSettings(intent);
            } catch (IllegalArgumentException e) {
                // A malformed filter (bad service_uuid, bad scan_mode) used to be
                // logged and then ignored, so the scan silently returned the whole
                // neighbourhood instead of the device that was asked for.
                returnError(this, intent, e.getMessage());
                return;
            }

            // Streaming is an explicit request and must NOT be inferred from the
            // presence of socket_output: SocketListener puts a socket_output on
            // EVERY request - its own fallback socket for a normal request
            // (SocketListener.java:399) and the client's own socket for a
            // streaming one (SocketListener.java:355) - so "socket_output !=
            // null" is true even for a plain `scan`. The old code used exactly
            // that test, so every scan took the streaming branch: BleScanWriter
            // connected to the fallback socket, wrote nothing until a device
            // appeared and never closed, the daemon's gate copied zero bytes and
            // gave up after 30 s. `adapter info` answered because it never
            // looked at socket_output at all.
            //
            // EXTRA_JSON_ERRORS cannot be used as the discriminator either: it
            // is set on both branches (SocketListener.java:369 and :404).
            // Streaming therefore has to be asked for explicitly.
            final boolean streaming = intent.getBooleanExtra("streaming", false);
            String socketOutput = intent.getStringExtra("socket_output");
            if (streaming && (socketOutput == null || socketOutput.isEmpty())) {
                returnError(this, intent, "streaming requested but socket_output is missing");
                return;
            }

            if (streaming) {
                // Streaming mode — launch writer thread
                activeScanCallback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        if (activeScanWriter != null) {
                            activeScanWriter.addResult(callbackType, result);
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        Logger.logError(LOG_TAG, "BLE scan failed with error: " + errorCode);
                        if (activeScanWriter != null) {
                            activeScanWriter.onScanFailed(errorCode);
                        }
                    }
                };

                activeScanWriter = new BleScanWriter(socketOutput);
                activeScanWriter.setLimit(intent.getIntExtra("limit", BleScanWriter.DEFAULT_LIMIT));
                // A stream can be bounded by --duration. Without it the scan runs
                // until `le-scan stop` or the result limit closes the socket -
                // which is what a stream is for, but it has to be asked for.
                activeScanWriter.setDuration(intent.getIntExtra("duration",
                        intent.getIntExtra("timeout", 0)));
                activeScanWriter.start();

                try {
                    scanner.startScan(filters, settings, activeScanCallback);
                } catch (Exception e) {
                    activeScanCallback = null;
                    if (activeScanWriter != null) {
                        activeScanWriter.interrupt();
                        activeScanWriter = null;
                    }
                    returnError(this, intent, "BLE scan could not start: " + e.getMessage());
                    return;
                }

                // No reply() here: a streaming caller reads socket_output, and a
                // second connection to that address would race the writer.
                // BleScanWriter writes a scan_started line first, so the stream
                // never begins with silence either.
            } else {
                // Single-shot: collect results for `duration`, answer with one
                // array.
                //
                // The waiting must not run on this thread. onStartCommand() runs
                // on the main looper and the platform delivers ScanCallback on
                // that same looper, so the old lock.wait(timeout) blocked the
                // only thread able to deliver a result: scans came back empty
                // after stalling the service. Collect on a worker thread instead.
                final List<Map<String, Object>> results = new ArrayList<>();
                final Object lock = new Object();
                final CountDownLatch scanDone = new CountDownLatch(1);
                // The platform reports refusal asynchronously, so the code has to
                // survive until the answer is written, or an empty list is all the
                // caller sees.
                final int[] scanError = {-1};

                final ScanCallback callback = new ScanCallback() {
                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        synchronized (lock) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("device", result.getDevice());
                            map.put("rssi", result.getRssi());
                            map.put("result", result);
                            results.add(map);
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        Logger.logError(LOG_TAG, "BLE scan failed with error: " + errorCode);
                        scanError[0] = errorCode;
                        scanDone.countDown();
                    }
                };

                // The CLI sends --duration; older callers sent timeout. Accept
                // both, or the documented option is silently ignored.
                int requested = intent.getIntExtra("duration",
                        intent.getIntExtra("timeout", 5000));
                if (requested < 100) requested = 100;
                if (requested > 60000) requested = 60000;
                final int timeout = requested;

                try {
                    // Publish the callback as the active one: without this a
                    // `le-scan stop` arriving during the collect window could
                    // not stop anything, and `le-scan status` claimed no scan
                    // was running while one was.
                    activeScanCallback = callback;
                    scanner.startScan(filters, settings, callback);
                } catch (Exception e) {
                    activeScanCallback = null;
                    // A SecurityException here (permission revoked while the
                    // dialog was up, BLE disabled by the system) used to bubble
                    // into the generic handler and answer with an opaque
                    // "Internal error"; say what actually went wrong.
                    returnError(this, intent, "BLE scan could not start: " + e.getMessage());
                    return;
                }

                final Context appContext = getApplicationContext();
                // Keep the service alive until the answer is written: stopSelf()
                // in onStartCommand() would otherwise tear it down mid-collect.
                keepAlive = true;
                new Thread(() -> {
                    try {
                        scanDone.await(timeout, TimeUnit.MILLISECONDS);

                        try {
                            scanner.stopScan(callback);
                        } catch (Exception e) {
                            // A vanished adapter throws here; dying before the
                            // reply would leave the caller without an answer.
                            Logger.logStackTraceWithMessage(LOG_TAG, "stopScan failed", e);
                        }

                    // Build result
                    reply(this, intent, new ResultReturner.ResultJsonWriter() {
                        @Override
                        public void writeJson(JsonWriter out) throws Exception {
                            out.beginObject();
                            out.name("results");
                            out.beginArray();
                            Map<String, Object>[] snap;
                            synchronized (lock) {
                                snap = results.toArray(new Map[0]);
                            }
                            for (Map<String, Object> entry : snap) {
                                ScanResult sr = (ScanResult) entry.get("result");
                                BluetoothDevice dev = sr.getDevice();
                                out.beginObject();
                                out.name("address").value(dev != null ? dev.getAddress() : "");
                                out.name("name").value(dev != null && dev.getName() != null
                                        ? dev.getName() : "");
                                out.name("rssi").value(sr.getRssi());
                                ScanRecord record = sr.getScanRecord();
                                if (record != null) {
                                    if (record.getServiceUuids() != null) {
                                        out.name("service_uuids");
                                        out.beginArray();
                                        for (ParcelUuid uuid : record.getServiceUuids()) {
                                            out.value(uuid.toString());
                                        }
                                        out.endArray();
                                    }
                                    SparseArray<byte[]> manuData = record.getManufacturerSpecificData();
                                    if (manuData != null && manuData.size() > 0) {
                                        out.name("manufacturer_data");
                                        out.beginObject();
                                        for (int i = 0; i < manuData.size(); i++) {
                                            out.name(String.valueOf(manuData.keyAt(i)))
                                                    .value(bytesToHexString(manuData.valueAt(i)));
                                        }
                                        out.endObject();
                                    }
                                }
                                out.endObject();
                            }
                            out.endArray();
                            if (snap.length == 0) {
                                // An empty list is ambiguous: nothing around, location
                                // services off (required for BLE scans up to Android 11) or
                                // the adapter went away. Name the cause instead of a bare [].
                                String why = scanError[0] != -1
                                        ? "Scan failed with error code " + scanError[0]
                                        : bleEmptyReason(appContext, adapter);
                                if (why != null) out.name("API_ERROR").value(why);
                            }
                            out.endObject();
                        }
                    });
                    } catch (Throwable t) {
                        // Anything escaping here would close the socket with zero
                        // bytes, which the caller can only read as a dead handler.
                        Logger.logStackTraceWithMessage(LOG_TAG, "BLE scan collect failed", t);
                        returnError(this, intent, "BLE scan failed: " + t);
                    } finally {
                        activeScanCallback = null;
                        keepAlive = false;
                        stopSelf();
                    }
                }, "ble-scan-collect").start();
            }
        }

        // GATT Client — Connect
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleGattConnect(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }

            String address = intent.getStringExtra("address");
            if (address == null) {
                returnError(this, intent, "Missing 'address' extra");
                return;
            }

            String action = intent.getStringExtra("action");
            if ("disconnect".equals(action)) {
                BluetoothGattContainer container = gattClients.remove(address);
                if (container != null) {
                    container.gatt.disconnect();
                    container.gatt.close();
                }
                reply(this, intent, out -> {
                    out.append("{\"status\":\"disconnected\",\"address\":\"" + escapeJson(address) + "\"}\n");
                });
                return;
            }

            // Check if already connected
            BluetoothGattContainer existing = gattClients.get(address);
            if (existing != null) {
                // Return current state
                String stateStr;
                switch (existing.connectionState) {
                    case BluetoothGatt.STATE_CONNECTED: stateStr = "connected"; break;
                    case BluetoothGatt.STATE_CONNECTING: stateStr = "connecting"; break;
                    case BluetoothGatt.STATE_DISCONNECTING: stateStr = "disconnecting"; break;
                    default: stateStr = "disconnected"; break;
                }
                reply(this, intent, out -> {
                    out.append("{\"status\":\"already_registered\",\"state\":\"" + stateStr + "\"}\n");
                });
                return;
            }

            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device == null) {
                returnError(this, intent, "Invalid Bluetooth address: " + address);
                return;
            }

            final BluetoothGattContainer container = new BluetoothGattContainer(null);

            BluetoothGattCallback callback = new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    container.connectionState = newState;
                    if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                        container.gatt = gatt;
                        gattClients.put(address, container);
                    }
                    container.opSemaphore.release();
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    container.opSemaphore.release();
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                    container.opSemaphore.release();
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                    container.opSemaphore.release();
                }

                @Override
                public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                    container.opSemaphore.release();
                }

                @Override
                public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                    container.opSemaphore.release();
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                    // Notification received — if streaming is active, write to writer
                    Logger.logDebug(LOG_TAG, "Characteristic changed: " + characteristic.getUuid());
                }
            };

            // Perform connection
            BluetoothGatt gatt;
            boolean autoConnect = intent.getBooleanExtra("auto_connect", false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(this, autoConnect, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, autoConnect, callback);
            }

            if (gatt == null) {
                returnError(this, intent, "connectGatt returned null for " + address);
                return;
            }

            container.gatt = gatt;

            // Wait for connection result
            boolean acquired;
            try {
                acquired = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acquired = false;
            }

            String stateStr;
            boolean connected = false;
            switch (container.connectionState) {
                case BluetoothGatt.STATE_CONNECTED:
                    stateStr = "connected";
                    connected = true;
                    break;
                case BluetoothGatt.STATE_CONNECTING:
                    stateStr = "connecting";
                    break;
                default:
                    stateStr = "disconnected";
                    break;
            }

            if (!connected) {
                gattClients.remove(address);
                gatt.close();
            }

            final boolean isConnected = connected;
            final boolean isNotConnected = !connected;
            final boolean isNotAcquired = !acquired;
            reply(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("status").value(isConnected ? "connected" : "connection_failed");
                    out.name("address").value(address);
                    out.name("state").value(stateStr);
                    if (isNotConnected && isNotAcquired) {
                        out.name("error").value("Connection timed out after " + GATT_OPERATION_TIMEOUT_MS + "ms");
                    }
                    out.endObject();
                }
            });
        }

        // ============================================================
        // GATT Client — Discover Services
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleGattDiscover(Intent intent) {
            String address = intent.getStringExtra("address");
            if (address == null) {
                returnError(this, intent, "Missing 'address' extra");
                return;
            }

            BluetoothGattContainer container = gattClients.get(address);
            if (container == null || container.gatt == null) {
                returnError(this, intent, "Not connected to " + address + ". Use gatt-connect first.");
                return;
            }

            boolean discovered = container.gatt.discoverServices();
            if (!discovered) {
                returnError(this, intent, "discoverServices() returned false");
                return;
            }

            boolean acquired;
            try {
                acquired = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acquired = false;
            }

            if (!acquired) {
                returnError(this, intent, "Service discovery timed out");
                return;
            }

            List<BluetoothGattService> services = container.gatt.getServices();

            reply(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("address").value(address);
                    out.name("services");
                    out.beginArray();
                    for (BluetoothGattService svc : services) {
                        out.beginObject();
                        out.name("uuid").value(svc.getUuid().toString());
                        out.name("type").value(svc.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY ? "primary" : "secondary");
                        out.name("characteristics");
                        out.beginArray();
                        for (BluetoothGattCharacteristic chr : svc.getCharacteristics()) {
                            writeCharacteristicJson(out, chr);
                        }
                        out.endArray();
                        out.endObject();
                    }
                    out.endArray();
                    out.endObject();
                }
            });
        }

        private void writeCharacteristicJson(JsonWriter out, BluetoothGattCharacteristic chr) throws Exception {
            out.beginObject();
            out.name("uuid").value(chr.getUuid().toString());
            out.name("properties").value(chr.getProperties());
            out.name("permissions").value(chr.getPermissions());
            byte[] value = chr.getValue();
            if (value != null) {
                out.name("value").value(bytesToHexString(value));
            }
            out.name("descriptors");
            out.beginArray();
            for (BluetoothGattDescriptor desc : chr.getDescriptors()) {
                out.beginObject();
                out.name("uuid").value(desc.getUuid().toString());
                byte[] descValue = desc.getValue();
                if (descValue != null) {
                    out.name("value").value(bytesToHexString(descValue));
                }
                out.name("permissions").value(desc.getPermissions());
                out.endObject();
            }
            out.endArray();
            out.endObject();
        }

        // ============================================================
        // GATT Client — Read Characteristic / Descriptor
        // ============================================================

        // ── Bond / RSSI / battery probe ────────────────────────────────

        /** Standard GATT Battery Service and Battery Level characteristic. */
        private static final UUID BATTERY_SERVICE_UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
        private static final UUID BATTERY_LEVEL_UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

        /**
         * Pair, unpair or cancel pairing.
         *
         * createBond() is public API; removeBond() and cancelBondProcess() are
         * hidden, so they are invoked reflectively and reported as unavailable
         * when a build does not expose them.
         */
        private void handleBond(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }
            String address = intent.getStringExtra("address");
            if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
                returnError(this, intent, "Missing or invalid 'address' extra");
                return;
            }
            String requested = intent.getStringExtra("action");
            final String action = requested != null ? requested : "pair";

            BluetoothDevice device = adapter.getRemoteDevice(address);
            String status;
            String note = null;

            if ("unpair".equals(action) || "remove".equals(action)) {
                boolean ok = Boolean.TRUE.equals(readProperty(device, "removeBond"));
                status = ok ? "unpair_started" : "failed";
                if (!ok) note = "removeBond() is unavailable or returned false";
            } else if ("cancel".equals(action)) {
                boolean ok = Boolean.TRUE.equals(readProperty(device, "cancelBondProcess"));
                status = ok ? "cancel_started" : "failed";
                if (!ok) note = "cancelBondProcess() is unavailable or returned false";
            } else {
                boolean ok = device.createBond();
                status = ok ? "bonding_started" : "failed";
                if (!ok) note = "createBond() returned false (already bonding, or permission denied)";
            }

            final String finalStatus = status;
            final String finalNote = note;
            final String bondState = bondStateToString(device.getBondState());
            final String deviceName;
            try {
                deviceName = device.getName();
            } catch (Exception e) {
                returnError(this, intent, "Cannot read device name: " + e.getMessage());
                return;
            }

            reply(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("status").value(finalStatus);
                    out.name("action").value(action);
                    out.name("address").value(address);
                    if (deviceName != null) out.name("name").value(deviceName);
                    out.name("bond_state").value(bondState);
                    if (finalNote != null) out.name("api_note").value(finalNote);
                    out.endObject();
                }
            });
        }

        /** Battery level (0x2A19) plus RSSI of a short-lived connection. */
        private void handleBatteryLevel(Intent intent) {
            probeDevice(intent, true);
        }

        /** RSSI of a short-lived connection. */
        private void handleGattRssi(Intent intent) {
            probeDevice(intent, false);
        }

        /**
         * Connect, optionally read the standard Battery Level characteristic, read
         * the RSSI, then disconnect.
         *
         * This is deliberately self-contained instead of reusing gattClients: the
         * user asks "how full are my earbuds / mouse" far more often than they
         * keep a GATT session open, and the probe must not disturb one that is.
         */
        private void probeDevice(Intent intent, final boolean wantBattery) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }
            String address = intent.getStringExtra("address");
            if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
                returnError(this, intent, "Missing or invalid 'address' extra");
                return;
            }

            final BluetoothDevice device = adapter.getRemoteDevice(address);
            final BluetoothGattContainer container = new BluetoothGattContainer(null);
            final int[] level = {-1};
            final int[] rssi = {Integer.MIN_VALUE};
            final String[] error = {null};
            final boolean[] finished = {false};
            final boolean[] batteryValueSeen = {false};

            BluetoothGattCallback callback = new BluetoothGattCallback() {
                private void finish() {
                    if (finished[0]) return;
                    finished[0] = true;
                    container.opSemaphore.release();
                }

                private void requestRssi() {
                    if (container.gatt != null && !container.gatt.readRemoteRssi()) {
                        if (error[0] == null) error[0] = "readRemoteRssi() returned false";
                        finish();
                    }
                }

                private void applyBatteryValue(byte[] value, int status) {
                    if (batteryValueSeen[0]) return;
                    batteryValueSeen[0] = true;
                    if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.length == 0) {
                        if (error[0] == null) error[0] = "Battery read failed (status " + status + ")";
                    } else {
                        level[0] = value[0] & 0xFF;
                    }
                    requestRssi();
                }

                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    container.connectionState = newState;
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        if (!gatt.discoverServices()) {
                            if (error[0] == null) error[0] = "discoverServices() returned false";
                            finish();
                        }
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        if (error[0] == null && level[0] < 0 && rssi[0] == Integer.MIN_VALUE) {
                            error[0] = "Disconnected before a result was read (status " + status + ")";
                        }
                        finish();
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (error[0] == null) error[0] = "Service discovery failed (status " + status + ")";
                        finish();
                        return;
                    }
                    if (!wantBattery) {
                        requestRssi();
                        return;
                    }
                    BluetoothGattService service = gatt.getService(BATTERY_SERVICE_UUID);
                    BluetoothGattCharacteristic characteristic =
                        service != null ? service.getCharacteristic(BATTERY_LEVEL_UUID) : null;
                    if (characteristic == null) {
                        error[0] = "Device exposes no Battery Level characteristic (0x2A19)";
                        finish();
                        return;
                    }
                    if (!gatt.readCharacteristic(characteristic)) {
                        error[0] = "readCharacteristic() returned false";
                        finish();
                    }
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                    applyBatteryValue(characteristic.getValue(), status);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                                 byte[] value, int status) {
                    // API 33+ delivers the value separately; the base class also
                    // calls the deprecated overload, hence the seen-guard.
                    applyBatteryValue(value, status);
                }

                @Override
                public void onReadRemoteRssi(BluetoothGatt gatt, int value, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        rssi[0] = value;
                    } else if (error[0] == null) {
                        error[0] = "RSSI read failed (status " + status + ")";
                    }
                    finish();
                }
            };

            container.gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (container.gatt == null) {
                returnError(this, intent, "connectGatt() returned null");
                return;
            }

            boolean completed;
            try {
                completed = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                completed = false;
            }

            try {
                container.gatt.disconnect();
            } catch (Exception ignored) { }
            try {
                container.gatt.close();
            } catch (Exception ignored) { }

            final boolean timedOut = !completed;
            String resolvedName = null;
            try {
                resolvedName = device.getName();
            } catch (Exception ignored) { }
            final String deviceName = resolvedName;

            reply(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("address").value(device.getAddress());
                    if (deviceName != null) out.name("name").value(deviceName);
                    if (level[0] >= 0) out.name("battery_level").value(level[0]);
                    if (rssi[0] != Integer.MIN_VALUE) out.name("rssi").value(rssi[0]);
                    if (error[0] != null) out.name("API_ERROR").value(error[0]);
                    if (timedOut) {
                        out.name("status_note").value(
                            "No response within " + GATT_OPERATION_TIMEOUT_MS + " ms; the device may be out of range or busy");
                    }
                    out.endObject();
                }
            });
        }

        @SuppressLint("MissingPermission")
        private void handleGattRead(Intent intent) {
            String address = intent.getStringExtra("address");
            if (address == null) {
                returnError(this, intent, "Missing 'address' extra");
                return;
            }

            BluetoothGattContainer container = gattClients.get(address);
            if (container == null || container.gatt == null) {
                returnError(this, intent, "Not connected to " + address);
                return;
            }

            String characteristicUuid = intent.getStringExtra("characteristic_uuid");
            if (characteristicUuid == null) {
                returnError(this, intent, "Missing 'characteristic_uuid' extra");
                return;
            }

            // Find the characteristic
            BluetoothGattCharacteristic characteristic = findCharacteristic(container.gatt, characteristicUuid);
            if (characteristic == null) {
                returnError(this, intent, "Characteristic " + characteristicUuid + " not found");
                return;
            }

            // Check if reading a descriptor instead
            String descriptorUuid = intent.getStringExtra("descriptor_uuid");
            if (descriptorUuid != null) {
                BluetoothGattDescriptor descriptor = findDescriptor(characteristic, descriptorUuid);
                if (descriptor == null) {
                    returnError(this, intent, "Descriptor " + descriptorUuid + " not found");
                    return;
                }
                boolean read = container.gatt.readDescriptor(descriptor);
                if (!read) {
                    returnError(this, intent, "readDescriptor() returned false");
                    return;
                }
                boolean acquired;
                try {
                    acquired = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    acquired = false;
                }
                if (!acquired) {
                    returnError(this, intent, "Descriptor read timed out");
                    return;
                }
                final byte[] descVal = descriptor.getValue();
                reply(this, intent, new ResultReturner.ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        out.beginObject();
                        out.name("type").value("descriptor");
                        out.name("uuid").value(descriptorUuid);
                        out.name("value").value(descVal != null ? bytesToHexString(descVal) : "");
                        out.endObject();
                    }
                });
                return;
            }

            // Read characteristic
            boolean read = container.gatt.readCharacteristic(characteristic);
            if (!read) {
                returnError(this, intent, "readCharacteristic() returned false");
                return;
            }

            boolean acquired;
            try {
                acquired = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acquired = false;
            }

            if (!acquired) {
                returnError(this, intent, "Characteristic read timed out");
                return;
            }

            final byte[] chrValue = characteristic.getValue();
            reply(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    out.beginObject();
                    out.name("type").value("characteristic");
                    out.name("uuid").value(characteristicUuid);
                    out.name("value").value(chrValue != null ? bytesToHexString(chrValue) : "");
                    out.endObject();
                }
            });
        }

        private static BluetoothGattCharacteristic findCharacteristic(BluetoothGatt gatt, String uuidStr) {
            UUID targetUuid = UUID.fromString(uuidStr);
            for (BluetoothGattService svc : gatt.getServices()) {
                for (BluetoothGattCharacteristic chr : svc.getCharacteristics()) {
                    if (chr.getUuid().equals(targetUuid)) {
                        return chr;
                    }
                }
            }
            return null;
        }

        private static BluetoothGattDescriptor findDescriptor(BluetoothGattCharacteristic chr, String uuidStr) {
            UUID targetUuid = UUID.fromString(uuidStr);
            for (BluetoothGattDescriptor desc : chr.getDescriptors()) {
                if (desc.getUuid().equals(targetUuid)) {
                    return desc;
                }
            }
            return null;
        }

        // ============================================================
        // GATT Client — Write Characteristic / Descriptor
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleGattWrite(Intent intent) {
            String address = intent.getStringExtra("address");
            if (address == null) {
                returnError(this, intent, "Missing 'address' extra");
                return;
            }

            BluetoothGattContainer container = gattClients.get(address);
            if (container == null || container.gatt == null) {
                returnError(this, intent, "Not connected to " + address);
                return;
            }

            String characteristicUuid = intent.getStringExtra("characteristic_uuid");
            if (characteristicUuid == null) {
                returnError(this, intent, "Missing 'characteristic_uuid' extra");
                return;
            }

            String valueHex = intent.getStringExtra("value");
            if (valueHex == null) {
                returnError(this, intent, "Missing 'value' extra (hex string)");
                return;
            }

            BluetoothGattCharacteristic characteristic = findCharacteristic(container.gatt, characteristicUuid);
            if (characteristic == null) {
                returnError(this, intent, "Characteristic " + characteristicUuid + " not found");
                return;
            }

            byte[] value = hexStringToBytes(valueHex);

            // Check for descriptor write
            String descriptorUuid = intent.getStringExtra("descriptor_uuid");
            if (descriptorUuid != null) {
                BluetoothGattDescriptor descriptor = findDescriptor(characteristic, descriptorUuid);
                if (descriptor == null) {
                    returnError(this, intent, "Descriptor " + descriptorUuid + " not found");
                    return;
                }
                descriptor.setValue(value);
                boolean written = container.gatt.writeDescriptor(descriptor);
                if (!written) {
                    returnError(this, intent, "writeDescriptor() returned false");
                    return;
                }
                boolean acquired;
                try {
                    acquired = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    acquired = false;
                }
                if (!acquired) {
                    returnError(this, intent, "Descriptor write timed out");
                    return;
                }
                reply(this, intent, out -> {
                    out.append("{\"status\":\"descriptor_written\",\"uuid\":\"" + escapeJson(descriptorUuid) + "\"}\n");
                });
                return;
            }

            // Write characteristic
            characteristic.setValue(value);

            // Set write type if specified
            String writeType = intent.getStringExtra("write_type");
            if ("no_response".equals(writeType)) {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else if ("signed".equals(writeType)) {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_SIGNED);
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }

            boolean written = container.gatt.writeCharacteristic(characteristic);
            if (!written) {
                returnError(this, intent, "writeCharacteristic() returned false");
                return;
            }

            boolean acquired;
            try {
                acquired = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acquired = false;
            }

            if (!acquired) {
                returnError(this, intent, "Characteristic write timed out");
                return;
            }

            reply(this, intent, out -> {
                out.append("{\"status\":\"characteristic_written\",\"uuid\":\"" + escapeJson(characteristicUuid) + "\"}\n");
            });
        }

        // ============================================================
        // GATT Client — Set Notification
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleGattNotify(Intent intent) {
            String address = intent.getStringExtra("address");
            if (address == null) {
                returnError(this, intent, "Missing 'address' extra");
                return;
            }

            BluetoothGattContainer container = gattClients.get(address);
            if (container == null || container.gatt == null) {
                returnError(this, intent, "Not connected to " + address);
                return;
            }

            String characteristicUuid = intent.getStringExtra("characteristic_uuid");
            if (characteristicUuid == null) {
                returnError(this, intent, "Missing 'characteristic_uuid' extra");
                return;
            }

            BluetoothGattCharacteristic characteristic = findCharacteristic(container.gatt, characteristicUuid);
            if (characteristic == null) {
                returnError(this, intent, "Characteristic " + characteristicUuid + " not found");
                return;
            }

            // Get the CCCD (Client Characteristic Configuration Descriptor)
            BluetoothGattDescriptor cccd = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (cccd == null) {
                returnError(this, intent, "CCCD not found for characteristic " + characteristicUuid);
                return;
            }

            boolean enable = intent.getBooleanExtra("enable", true);

            // Enable notification locally
            boolean notifSet = container.gatt.setCharacteristicNotification(characteristic, enable);
            if (!notifSet) {
                returnError(this, intent, "setCharacteristicNotification() returned false");
                return;
            }

            // Write CCCD descriptor
            byte[] cccdValue;
            if (enable) {
                // Check if characteristic supports indicate (0x20) or notify (0x10)
                int properties = characteristic.getProperties();
                boolean supportsIndicate = (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                if (supportsIndicate) {
                    cccdValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                } else {
                    cccdValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                }
            } else {
                cccdValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            }

            cccd.setValue(cccdValue);
            boolean descWritten = container.gatt.writeDescriptor(cccd);
            if (!descWritten) {
                returnError(this, intent, "writeDescriptor(CCCD) returned false");
                return;
            }

            boolean acquired;
            try {
                acquired = container.opSemaphore.tryAcquire(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acquired = false;
            }

            if (!acquired) {
                returnError(this, intent, "CCCD write timed out");
                return;
            }

            reply(this, intent, out -> {
                out.append("{\"status\":\"notification_" + (enable ? "enabled" : "disabled") +
                        "\",\"uuid\":\"" + escapeJson(characteristicUuid) + "\"}\n");
            });
        }

        // ============================================================
        // BLE advertising
        // ============================================================

        /**
         * Advertise over BLE, which is the half of the GATT story the client was
         * missing: openGattServer() makes the device attachable but invisible
         * unless it also advertises, so a peer scanning the air saw nothing.
         */
        @SuppressLint("MissingPermission")
        private void handleAdvertise(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }
            if (!adapter.isEnabled()) {
                returnError(this, intent, "Bluetooth is off - turn it on and retry");
                return;
            }

            String action = intent.getStringExtra("action");
            if (action == null) action = "status";

            switch (action) {
                case "status": {
                    reply(this, intent, out -> {
                        JSONObject obj = new JSONObject();
                        obj.put("supported", adapter.isMultipleAdvertisementSupported());
                        obj.put("advertising", activeAdvertiseCallback != null);
                        if (advertiseName != null) obj.put("name", advertiseName);
                        if (advertiseServiceUuid != null) obj.put("service_uuid", advertiseServiceUuid);
                        out.append(obj.toString()).append("\n");
                    });
                    return;
                }
                case "stop": {
                    if (advertiserInstance == null || activeAdvertiseCallback == null) {
                        returnError(this, intent, "Not advertising");
                        return;
                    }
                    try {
                        advertiserInstance.stopAdvertising(activeAdvertiseCallback);
                    } catch (Exception e) {
                        // The adapter can disappear mid-flight; the advertiser is
                        // gone either way, so drop the reference and say so.
                        Logger.logWarn(LOG_TAG, "stopAdvertising failed: " + e.getMessage());
                    }
                    activeAdvertiseCallback = null;
                    advertiserInstance = null;
                    advertiseName = null;
                    advertiseServiceUuid = null;
                    reply(this, intent, out -> {
                        JSONObject obj = new JSONObject();
                        obj.put("advertising", false);
                        out.append(obj.toString()).append("\n");
                    });
                    return;
                }
                case "start":
                    break;
                default:
                    returnError(this, intent, "Unknown advertise action: " + action);
                    return;
            }

            if (!adapter.isMultipleAdvertisementSupported()) {
                returnError(this, intent,
                    "This device cannot advertise over BLE (isMultipleAdvertisementSupported() is false)");
                return;
            }
            if (activeAdvertiseCallback != null) {
                returnError(this, intent, "Already advertising - stop the running advertisement first");
                return;
            }

            final BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                returnError(this, intent, "getBluetoothLeAdvertiser() returned null");
                return;
            }

            AdvertiseSettings.Builder settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(advertiseMode(intent.getStringExtra("mode")))
                .setTxPowerLevel(advertiseTxPower(intent.getStringExtra("tx_power")))
                .setConnectable(intent.getBooleanExtra("connectable", true));
            int timeout = intent.getIntExtra("timeout", 0);
            // AdvertiseSettings.setTimeout() rejects anything outside
            // 0..180000 with IllegalArgumentException, which build() throws on
            // this thread: clamp instead of answering "Internal error".
            if (timeout > 0) settings.setTimeout(Math.min(timeout, 180000));

            String name = intent.getStringExtra("device_name");
            String serviceUuid = intent.getStringExtra("service_uuid");

            AdvertiseData.Builder data = new AdvertiseData.Builder();
            if (name != null && !name.isEmpty()) {
                // The platform advertises the adapter's own name; a custom one
                // cannot be set here, so a caller asking for it gets told that
                // instead of a silently different advertisement.
                if (!name.equals(adapter.getName())) {
                    returnError(this, intent, "Cannot advertise a custom device name (adapter name is '"
                        + adapter.getName() + "'); rename the adapter or omit device_name");
                    return;
                }
                data.setIncludeDeviceName(true);
            }
            if (serviceUuid != null && !serviceUuid.isEmpty()) {
                try {
                    data.addServiceUuid(ParcelUuid.fromString(normalizeUuid(serviceUuid)));
                } catch (IllegalArgumentException e) {
                    returnError(this, intent, "Invalid service_uuid: " + serviceUuid);
                    return;
                }
            }
            if (intent.hasExtra("manufacturer_id")) {
                byte[] payload = new byte[0];
                String hex = intent.getStringExtra("manufacturer_data");
                if (hex != null && !hex.isEmpty()) {
                    payload = hexStringToBytes(hex);
                    if (payload == null) {
                        returnError(this, intent, "Invalid manufacturer_data: " + hex);
                        return;
                    }
                }
                data.addManufacturerData(intent.getIntExtra("manufacturer_id", 0), payload);
            }
            data.setIncludeTxPowerLevel(intent.getBooleanExtra("include_tx_power", false));

            final CountDownLatch latch = new CountDownLatch(1);
            final String[] failure = new String[1];
            AdvertiseCallback callback = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    latch.countDown();
                }

                @Override
                public void onStartFailure(int errorCode) {
                    failure[0] = advertiseError(errorCode);
                    latch.countDown();
                }
            };

            final AdvertiseSettings builtSettings;
            final AdvertiseData builtData;
            try {
                builtSettings = settings.build();
                builtData = data.build();
            } catch (IllegalArgumentException e) {
                // build() validates the 31-byte legacy payload limit and the
                // settings ranges; throwing here used to surface as a bare
                // "Internal error" because nothing named the offending extras.
                returnError(this, intent, "Invalid advertisement (payload too large or "
                        + "settings out of range): " + e.getMessage());
                return;
            }
            final boolean connectable = intent.getBooleanExtra("connectable", true);
            final String mode = intent.getStringExtra("mode") == null
                    ? "balanced" : intent.getStringExtra("mode");

            // startAdvertising() is asynchronous and the platform posts its
            // AdvertiseCallback on the main looper - the very thread
            // onStartCommand() runs on. Waiting for that callback there could
            // never succeed: the old latch.await(5 s) blocked the looper that had
            // to signal the latch, so `advertise start` always stalled five
            // seconds and then reported "Advertising did not start within 5 s"
            // (or an ANR on a slow device) whatever the radio actually did.
            keepAlive = true;
            new Thread(() -> {
                try {
                    try {
                        advertiser.startAdvertising(builtSettings, builtData, callback);
                    } catch (Exception e) {
                        returnError(this, intent, "startAdvertising failed: " + e);
                        return;
                    }

                    boolean started;
                    try {
                        // Reporting success before onStartSuccess would tell the
                        // caller something is on the air when the platform may
                        // have rejected the settings outright.
                        started = latch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        started = false;
                    }

                    if (!started) {
                        try {
                            advertiser.stopAdvertising(callback);
                        } catch (Exception e) {
                            Logger.logStackTraceWithMessage(LOG_TAG, "stopAdvertising failed", e);
                        }
                        returnError(this, intent, "Advertising did not start within 5 s");
                        return;
                    }
                    if (failure[0] != null) {
                        returnError(this, intent, failure[0]);
                        return;
                    }

                    advertiserInstance = advertiser;
                    activeAdvertiseCallback = callback;
                    advertiseName = name;
                    advertiseServiceUuid = serviceUuid;

                    reply(this, intent, out -> {
                        JSONObject obj = new JSONObject();
                        obj.put("advertising", true);
                        obj.put("connectable", connectable);
                        obj.put("mode", mode);
                        if (name != null) obj.put("name", name);
                        if (serviceUuid != null) obj.put("service_uuid", serviceUuid);
                        out.append(obj.toString()).append("\n");
                    });
                } finally {
                    keepAlive = false;
                    stopSelf();
                }
            }, "ble-advertise").start();
        }

        private static int advertiseMode(String mode) {
            if ("low_latency".equalsIgnoreCase(mode)) return AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY;
            if ("low_power".equalsIgnoreCase(mode)) return AdvertiseSettings.ADVERTISE_MODE_LOW_POWER;
            return AdvertiseSettings.ADVERTISE_MODE_BALANCED;
        }

        private static int advertiseTxPower(String level) {
            if ("high".equalsIgnoreCase(level)) return AdvertiseSettings.ADVERTISE_TX_POWER_HIGH;
            if ("low".equalsIgnoreCase(level)) return AdvertiseSettings.ADVERTISE_TX_POWER_LOW;
            if ("ultra_low".equalsIgnoreCase(level)) return AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW;
            return AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;
        }

        private static String advertiseError(int code) {
            switch (code) {
                case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                    return "advertise failed (1): the payload is larger than a single advertisement allows";
                case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    return "advertise failed (2): no free advertiser slot on this adapter";
                case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                    return "advertise failed (3): an advertisement is already running";
                case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                    return "advertise failed (4): internal error in the Bluetooth stack";
                case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    return "advertise failed (5): this device does not support BLE advertising";
                default:
                    return "advertise failed (" + code + ")";
            }
        }

        /** Accepts both "180d" and "0000180d-0000-1000-8000-00805f9b34fb". */
        private static String normalizeUuid(String uuid) {
            String trimmed = uuid.trim();
            if (trimmed.length() == 4) return "0000" + trimmed + "-0000-1000-8000-00805f9b34fb";
            if (trimmed.length() == 8) return trimmed + "-0000-1000-8000-00805f9b34fb";
            return trimmed;
        }

        // ============================================================
        // GATT Server
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleGattServer(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }

            String action = intent.getStringExtra("action");
            if (action == null) action = "status";

            switch (action) {
                case "start": {
                    if (gattServerInstance != null) {
                        returnError(this, intent, "GATT server already running");
                        return;
                    }
                    BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
                    if (manager == null) {
                        returnError(this, intent, "BluetoothManager not available");
                        return;
                    }
                    gattServerInstance = manager.openGattServer(this, new BluetoothGattServerCallback() {
                        @Override
                        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                            Logger.logDebug(LOG_TAG, "GATT server connection state: " + device.getAddress()
                                    + " -> " + newState);
                        }

                        @Override
                        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                                                                 int offset, BluetoothGattCharacteristic characteristic) {
                            Logger.logDebug(LOG_TAG, "Read request for " + characteristic.getUuid());
                            gattServerInstance.sendResponse(device, requestId,
                                    BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());
                        }

                        @Override
                        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                                 BluetoothGattCharacteristic characteristic,
                                                                 boolean preparedWrite, boolean responseNeeded,
                                                                 int offset, byte[] value) {
                            Logger.logDebug(LOG_TAG, "Write request for " + characteristic.getUuid());
                            characteristic.setValue(value);
                            if (responseNeeded) {
                                gattServerInstance.sendResponse(device, requestId,
                                        BluetoothGatt.GATT_SUCCESS, offset, value);
                            }
                        }

                        @Override
                        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                                             int offset, BluetoothGattDescriptor descriptor) {
                            Logger.logDebug(LOG_TAG, "Descriptor read request for " + descriptor.getUuid());
                            gattServerInstance.sendResponse(device, requestId,
                                    BluetoothGatt.GATT_SUCCESS, offset, descriptor.getValue());
                        }

                        @Override
                        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                                              BluetoothGattDescriptor descriptor,
                                                              boolean preparedWrite, boolean responseNeeded,
                                                              int offset, byte[] value) {
                            Logger.logDebug(LOG_TAG, "Descriptor write request for " + descriptor.getUuid());
                            descriptor.setValue(value);
                            if (responseNeeded) {
                                gattServerInstance.sendResponse(device, requestId,
                                        BluetoothGatt.GATT_SUCCESS, offset, value);
                            }
                        }
                    });

                    if (gattServerInstance == null) {
                        returnError(this, intent, "openGattServer() returned null");
                        return;
                    }

                    reply(this, intent, out -> {
                        out.append("{\"status\":\"gatt_server_started\"}\n");
                    });
                    break;
                }
                case "stop": {
                    if (gattServerInstance != null) {
                        gattServerInstance.clearServices();
                        gattServerInstance.close();
                        gattServerInstance = null;
                    }
                    reply(this, intent, out -> {
                        out.append("{\"status\":\"gatt_server_stopped\"}\n");
                    });
                    break;
                }
                case "add-service": {
                    if (gattServerInstance == null) {
                        returnError(this, intent, "GATT server not started. Use 'start' first.");
                        return;
                    }

                    String serviceUuid = intent.getStringExtra("service_uuid");
                    if (serviceUuid == null) {
                        returnError(this, intent, "Missing 'service_uuid' extra");
                        return;
                    }

                    boolean isPrimary = intent.getBooleanExtra("primary", true);
                    BluetoothGattService service = new BluetoothGattService(
                            UUID.fromString(serviceUuid),
                            isPrimary ? BluetoothGattService.SERVICE_TYPE_PRIMARY
                                      : BluetoothGattService.SERVICE_TYPE_SECONDARY);

                    // Add characteristics from extras (JSON array of characteristic definitions)
                    String characteristicsJson = intent.getStringExtra("characteristics");
                    if (characteristicsJson != null) {
                        try {
                            JSONArray chars = new JSONArray(characteristicsJson);
                            for (int i = 0; i < chars.length(); i++) {
                                JSONObject chrObj = chars.getJSONObject(i);
                                String chrUuid = chrObj.getString("uuid");
                                int properties = chrObj.optInt("properties",
                                        BluetoothGattCharacteristic.PROPERTY_READ);
                                int permissions = chrObj.optInt("permissions",
                                        BluetoothGattCharacteristic.PERMISSION_READ);

                                BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                                        UUID.fromString(chrUuid), properties, permissions);

                                // Optional initial value (hex)
                                if (chrObj.has("value")) {
                                    characteristic.setValue(hexStringToBytes(chrObj.getString("value")));
                                }

                                // Optional descriptors
                                if (chrObj.has("descriptors")) {
                                    JSONArray descs = chrObj.getJSONArray("descriptors");
                                    for (int j = 0; j < descs.length(); j++) {
                                        JSONObject descObj = descs.getJSONObject(j);
                                        String descUuid = descObj.getString("uuid");
                                        int descPerms = descObj.optInt("permissions",
                                                BluetoothGattDescriptor.PERMISSION_READ |
                                                BluetoothGattDescriptor.PERMISSION_WRITE);
                                        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                                                UUID.fromString(descUuid), descPerms);
                                        if (descObj.has("value")) {
                                            descriptor.setValue(hexStringToBytes(descObj.getString("value")));
                                        }
                                        characteristic.addDescriptor(descriptor);
                                    }
                                }

                                service.addCharacteristic(characteristic);
                            }
                        } catch (Exception e) {
                            returnError(this, intent, "Error parsing characteristics JSON: " + e.getMessage());
                            return;
                        }
                    }

                    boolean added = gattServerInstance.addService(service);
                    if (!added) {
                        returnError(this, intent, "addService() returned false");
                        return;
                    }

                    reply(this, intent, out -> {
                        out.append("{\"status\":\"service_added\",\"uuid\":\"" + escapeJson(serviceUuid) + "\"}\n");
                    });
                    break;
                }
                case "status":
                default: {
                    final boolean running = gattServerInstance != null;
                    reply(this, intent, new ResultReturner.ResultJsonWriter() {
                        @Override
                        public void writeJson(JsonWriter out) throws Exception {
                            out.beginObject();
                            out.name("running").value(running);
                            if (running && gattServerInstance.getServices() != null) {
                                out.name("services");
                                out.beginArray();
                                for (BluetoothGattService svc : gattServerInstance.getServices()) {
                                    out.beginObject();
                                    out.name("uuid").value(svc.getUuid().toString());
                                    out.name("type").value(svc.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY ? "primary" : "secondary");
                                    out.endObject();
                                }
                                out.endArray();
                            }
                            out.endObject();
                        }
                    });
                    break;
                }
            }
        }

        // ============================================================
        // RFCOMM Socket
        // ============================================================

        @SuppressLint("MissingPermission")
        private void handleRfcomm(Intent intent) {
            BluetoothAdapter adapter = getBluetoothAdapter(this);
            if (adapter == null) {
                returnError(this, intent, "Bluetooth not supported");
                return;
            }

            String address = intent.getStringExtra("address");
            if (address == null) {
                returnError(this, intent, "Missing 'address' extra");
                return;
            }

            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device == null) {
                returnError(this, intent, "Invalid Bluetooth address: " + address);
                return;
            }

            String uuidStr = intent.getStringExtra("uuid");
            UUID uuid;
            if (uuidStr != null) {
                try {
                    uuid = UUID.fromString(uuidStr);
                } catch (IllegalArgumentException e) {
                    returnError(this, intent, "Invalid UUID: " + uuidStr);
                    return;
                }
            } else {
                // Default Serial Port Profile UUID (SPP)
                uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
            }

            String action = intent.getStringExtra("action");
            if ("listen".equals(action)) {
                // Start listening mode
                try {
                    rfcommServerSocket = adapter.listenUsingRfcommWithServiceRecord(
                            intent.getStringExtra("name") != null ? intent.getStringExtra("name") : "TermuxAPI",
                            uuid);
                    reply(this, intent, out -> {
                        out.append("{\"status\":\"listening\",\"uuid\":\"" + uuid.toString() + "\"}\n");
                    });
                } catch (IOException e) {
                    returnError(this, intent, "listenUsingRfcommWithServiceRecord failed: " + e.getMessage());
                }
                return;
            }

            if ("accept".equals(action)) {
                // Accept incoming connection (blocking)
                if (rfcommServerSocket == null) {
                    returnError(this, intent, "No listening socket. Use 'listen' first.");
                    return;
                }
                try {
                    BluetoothSocket incomingSocket = rfcommServerSocket.accept(10000);
                    if (incomingSocket == null) {
                        returnError(this, intent, "accept() returned null (timeout)");
                        return;
                    }
                    // Extract file descriptor via reflection
                    reply(this, intent, new ResultReturner.WithAncillaryFd() {
                        @Override
                        public void writeResult(PrintWriter out) {
                            try {
                                Method m = incomingSocket.getClass().getMethod("getFileDescriptor");
                                Object fdObj = m.invoke(incomingSocket);
                                if (fdObj instanceof java.io.FileDescriptor) {
                                    java.io.FileDescriptor fd = (java.io.FileDescriptor) fdObj;
                                    Field fdField = fd.getClass().getDeclaredField("descriptor");
                                    fdField.setAccessible(true);
                                    int fdInt = fdField.getInt(fd);
                                    sendFd(out, fdInt);
                                } else {
                                    out.append("{\"error\":\"Could not extract file descriptor\"}\n");
                                }
                            } catch (Exception e) {
                                Logger.logStackTraceWithMessage(LOG_TAG, "RFCOMM accept fd error", e);
                                out.append("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n");
                            }
                        }
                    });
                } catch (IOException e) {
                    returnError(this, intent, "accept() failed: " + e.getMessage());
                }
                return;
            }

            // Connect to remote device (default action)
            BluetoothSocket socket;
            try {
                socket = device.createRfcommSocketToServiceRecord(uuid);
            } catch (IOException e) {
                returnError(this, intent, "createRfcommSocketToServiceRecord failed: " + e.getMessage());
                return;
            }

            // Cancel discovery (may slow connection)
            adapter.cancelDiscovery();

            // Connect
            try {
                socket.connect();
            } catch (IOException e) {
                try { socket.close(); } catch (IOException ignored) {}
                returnError(this, intent, "RFCOMM connect failed: " + e.getMessage());
                return;
            }

            // Use WithAncillaryFd to return the file descriptor
            reply(this, intent, new ResultReturner.WithAncillaryFd() {
                @Override
                public void writeResult(PrintWriter out) {
                    try {
                        Method m = socket.getClass().getMethod("getFileDescriptor");
                        Object fdObj = m.invoke(socket);
                        if (fdObj instanceof java.io.FileDescriptor) {
                            java.io.FileDescriptor fd = (java.io.FileDescriptor) fdObj;
                            Field fdField = fd.getClass().getDeclaredField("descriptor");
                            fdField.setAccessible(true);
                            int fdInt = fdField.getInt(fd);
                            sendFd(out, fdInt);
                        } else {
                            out.append("{\"error\":\"Could not extract file descriptor\"}\n");
                        }
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "RFCOMM fd error", e);
                        out.append("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}\n");
                    }
                }
            });
        }
    }

    // ============================================================
    // BleScanWriter (streaming BLE scan results)
    // ============================================================

    /**
     * Thread that writes BLE scan results to a LocalSocket, same pattern as
     * SensorAPI.SensorOutputWriter.
     */
    static class BleScanWriter extends Thread {

        static final int DEFAULT_DELAY = 500;
        static final int DEFAULT_LIMIT = Integer.MAX_VALUE;

        private final String outputSocketAddress;
        private volatile boolean isRunning;
        private int delay = DEFAULT_DELAY;
        private int limit = DEFAULT_LIMIT;
        private int counter;
        /** Upper bound for the stream in milliseconds; 0 means "until stopped". */
        private int duration;

        // Thread-safe queue of JSON strings to write
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

        BleScanWriter(String outputSocketAddress) {
            this.outputSocketAddress = outputSocketAddress;
        }

        void setDelay(int delay) {
            this.delay = delay;
        }

        void setLimit(int limit) {
            this.limit = limit;
        }

        void setDuration(int duration) {
            this.duration = duration;
        }

        void addResult(int callbackType, ScanResult result) {
            JSONObject obj = new JSONObject();
            try {
                JSONObject deviceObj = new JSONObject();
                BluetoothDevice dev = result.getDevice();
                deviceObj.put("address", dev != null ? dev.getAddress() : "");
                deviceObj.put("name", dev != null && dev.getName() != null ? dev.getName() : "");
                obj.put("device", deviceObj);
                obj.put("rssi", result.getRssi());
                obj.put("callback_type", callbackType);

                ScanRecord record = result.getScanRecord();
                if (record != null) {
                    if (record.getServiceUuids() != null) {
                        JSONArray uuidArr = new JSONArray();
                        for (ParcelUuid uuid : record.getServiceUuids()) {
                            uuidArr.put(uuid.toString());
                        }
                        obj.put("service_uuids", uuidArr);
                    }
                    SparseArray<byte[]> manuData = record.getManufacturerSpecificData();
                    if (manuData != null && manuData.size() > 0) {
                        JSONObject manuObj = new JSONObject();
                        for (int i = 0; i < manuData.size(); i++) {
                            manuObj.put(String.valueOf(manuData.keyAt(i)), bytesToHexString(manuData.valueAt(i)));
                        }
                        obj.put("manufacturer_data", manuObj);
                    }
                }

                queue.put(obj.toString());
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "BleScanWriter addResult error", e);
            }
        }

        void onScanFailed(int errorCode) {
            try {
                JSONObject err = new JSONObject();
                err.put("error", "scan_failed");
                err.put("error_code", errorCode);
                queue.put(err.toString());
            } catch (Exception e) {
                // ignore
            }
        }

        @Override
        public void run() {
            isRunning = true;
            counter = 0;

            try {
                try (LocalSocket outputSocket = new LocalSocket()) {
                    outputSocket.connect(ResultReturner.getApiLocalSocketAddress(
                            ResultReturner.context, "output", this.outputSocketAddress));

                    try (PrintWriter writer = new PrintWriter(outputSocket.getOutputStream())) {

                        // Announce before the first result: a stream that opens
                        // with silence is indistinguishable from a handler that
                        // never ran, and the caller cannot tell the two apart.
                        writer.write("{\"status\":\"scan_started\",\"streaming\":true}\n");
                        writer.flush();

                        final long deadline = duration > 0
                                ? System.currentTimeMillis() + duration : 0;

                        while (isRunning) {
                            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                                writer.write("{\"status\":\"scan_finished\",\"results\":"
                                        + counter + "}\n");
                                writer.flush();
                                break;
                            }

                            long wait = delay;
                            if (deadline > 0) {
                                wait = Math.max(1, Math.min(delay, deadline - System.currentTimeMillis()));
                            }

                            String json = queue.poll(wait, TimeUnit.MILLISECONDS);
                            if (json != null) {
                                writer.write(json + "\n");
                                writer.flush();

                                if (++counter >= limit) {
                                    Logger.logInfo(LOG_TAG, "BLE scan limit reached, stopping");
                                    break;
                                }
                            }
                        }
                        Logger.logInfo(LOG_TAG, "BleScanWriter finished");
                    }
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "BleScanWriter error", e);
            } finally {
                isRunning = false;
                // Stop the actual scan if we still own it
                if (activeScanCallback != null) {
                    try {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter != null && adapter.isEnabled()) {
                            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                            if (scanner != null) {
                                scanner.stopScan(activeScanCallback);
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                    activeScanCallback = null;
                }
            }
        }

        @Override
        public void interrupt() {
            super.interrupt();
            this.isRunning = false;
        }
    }
}
