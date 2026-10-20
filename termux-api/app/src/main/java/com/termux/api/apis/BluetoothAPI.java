package com.termux.api.apis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
            ResultReturner.returnData(apiReceiver, intent, out -> {
                out.append("{\"error\":\"Missing 'command' extra\"}\n");
            });
            return;
        }

        // Check runtime permissions for API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (needsBluetoothPermission(context, command)) {
                TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN);
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

        // Delegate to service for background work
        Intent serviceIntent = new Intent(context, BluetoothService.class);
        serviceIntent.setAction(intent.getAction());
        Bundle extras = intent.getExtras();
        if (extras != null) serviceIntent.putExtras(extras);
        context.startService(serviceIntent);
    }

    /**
     * Determine if the given command needs BLUETOOTH_CONNECT or BLUETOOTH_SCAN.
     */
    private static boolean needsBluetoothPermission(Context context, String command) {
        switch (command) {
            case "le-scan":
                return !PermissionUtils.checkPermission(context, Manifest.permission.BLUETOOTH_SCAN);
            case "gatt-server":
                return !PermissionUtils.checkPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE);
            default:
                return !PermissionUtils.checkPermission(context, Manifest.permission.BLUETOOTH_CONNECT);
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
     * Return an error JSON with the given message.
     */
    private static void returnError(Object receiver, Intent intent, String message) {
        ResultReturner.returnData(receiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                out.beginObject();
                out.name("error").value(message);
                out.endObject();
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
                Logger.logError(LOG_TAG, "Invalid service_uuid: " + serviceUuidStr);
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
                    case "gatt-server":
                        handleGattServer(intent);
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
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error handling command: " + command, e);
                returnError(this, intent, "Internal error: " + e.getMessage());
            }

            stopSelf(startId);
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
                    ResultReturner.returnData(this, intent, out -> {
                        out.append("{\"status\":\"enabled\"}\n");
                    });
                    break;
                case "disable":
                    adapter.disable();
                    ResultReturner.returnData(this, intent, out -> {
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
                    ResultReturner.returnData(this, intent, out -> {
                        out.append("{\"status\":\"name_set\",\"name\":\"" + escapeJson(name) + "\"}\n");
                    });
                    break;
                }
                case "info":
                default:
                    ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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
                            out.name("le_supported").value(
                                getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE));
                            ParcelUuid[] adapterUuids = adapter.getUuids();
                            out.name("uuids");
                            out.beginArray();
                            if (adapterUuids != null) {
                                for (ParcelUuid uuid : adapterUuids) out.value(uuid.toString());
                            }
                            out.endArray();
                            BluetoothClass adapterClass = adapter.getBluetoothClass();
                            if (adapterClass != null) {
                                out.name("device_class").value(adapterClass.getDeviceClass());
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
                                ParcelUuid[] deviceUuids = device.getUuids();
                                out.name("uuids");
                                out.beginArray();
                                if (deviceUuids != null) {
                                    for (ParcelUuid uuid : deviceUuids) out.value(uuid.toString());
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
                    ResultReturner.returnData(this, intent, out -> {
                        out.append("{\"status\":\"discovery_started\"}\n");
                    });
                    break;
                case "cancel":
                    adapter.cancelDiscovery();
                    ResultReturner.returnData(this, intent, out -> {
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
                    ResultReturner.returnData(this, intent, out -> {
                        out.append(jsonStr).append("\n");
                    });
                    break;
                case "status":
                default:
                    ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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

            ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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
                // Stop active scan
                if (activeScanCallback != null) {
                    scanner.stopScan(activeScanCallback);
                    activeScanCallback = null;
                }
                if (activeScanWriter != null) {
                    activeScanWriter.interrupt();
                    activeScanWriter = null;
                }
                ResultReturner.returnData(this, intent, out -> {
                    out.append("{\"status\":\"scan_stopped\"}\n");
                });
                return;
            }

            // Start scan
            List<ScanFilter> filters = buildScanFilters(intent);
            ScanSettings settings = buildScanSettings(intent);

            // Check if streaming output is requested
            String socketOutput = intent.getStringExtra("socket_output");

            if (socketOutput != null && !socketOutput.isEmpty()) {
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
                activeScanWriter.start();

                scanner.startScan(filters, settings, activeScanCallback);

                ResultReturner.returnData(this, intent, out -> {
                    out.append("{\"status\":\"scan_started\",\"streaming\":true}\n");
                });
            } else {
                // Single-shot: collect results until timeout
                final List<Map<String, Object>> results = new ArrayList<>();
                final Object lock = new Object();

                ScanCallback callback = new ScanCallback() {
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
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                };

                scanner.startScan(filters, settings, callback);

                // Wait for timeout or external stop condition
                int timeout = intent.getIntExtra("timeout", 5000);
                try {
                    synchronized (lock) {
                        lock.wait(timeout);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                scanner.stopScan(callback);

                // Build result
                ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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
                            out.beginObject();
                            BluetoothDevice dev = sr.getDevice();
                            out.name("address").value(dev != null ? dev.getAddress() : "");
                            out.name("name").value(dev != null && dev.getName() != null ? dev.getName() : "");
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
                                        out.name(String.valueOf(manuData.keyAt(i))).value(bytesToHexString(manuData.valueAt(i)));
                                    }
                                    out.endObject();
                                }
                            }
                            out.endObject();
                        }
                        out.endArray();
                        out.endObject();
                    }
                });
            }
        }

        // ============================================================
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
                ResultReturner.returnData(this, intent, out -> {
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
                ResultReturner.returnData(this, intent, out -> {
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
            ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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

            ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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
                ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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
            ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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
                ResultReturner.returnData(this, intent, out -> {
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

            ResultReturner.returnData(this, intent, out -> {
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

            ResultReturner.returnData(this, intent, out -> {
                out.append("{\"status\":\"notification_" + (enable ? "enabled" : "disabled") +
                        "\",\"uuid\":\"" + escapeJson(characteristicUuid) + "\"}\n");
            });
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

                    ResultReturner.returnData(this, intent, out -> {
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
                    ResultReturner.returnData(this, intent, out -> {
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

                    ResultReturner.returnData(this, intent, out -> {
                        out.append("{\"status\":\"service_added\",\"uuid\":\"" + escapeJson(serviceUuid) + "\"}\n");
                    });
                    break;
                }
                case "status":
                default: {
                    final boolean running = gattServerInstance != null;
                    ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
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
                    ResultReturner.returnData(this, intent, out -> {
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
                    ResultReturner.returnData(this, intent, new ResultReturner.WithAncillaryFd() {
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
            ResultReturner.returnData(this, intent, new ResultReturner.WithAncillaryFd() {
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

                        while (isRunning) {
                            String json = queue.poll(delay, TimeUnit.MILLISECONDS);
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
