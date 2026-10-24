package texmux.api.apis;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.system.Os;
import android.util.JsonWriter;
import android.util.SparseArray;

import texmux.api.util.ResultReturner;
import texmux.shared.logger.Logger;
import texmux.shared.termux.TermuxConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;

public class UsbAPI {

    protected static final String LOG_TAG = "UsbAPI";

    protected static SparseArray<UsbDeviceConnection> openDevices = new SparseArray<>();

    protected static final String ACTION_USB_PERMISSION = TermuxConstants.TERMUX_API_PACKAGE_NAME + ".USB_PERMISSION";

    public static void onReceive(final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        Intent serviceIntent = new Intent(context, UsbService.class);
        serviceIntent.setAction(intent.getAction());
        Bundle extras = intent.getExtras();
        if (extras != null)
            serviceIntent.putExtras(extras);
        context.startService(serviceIntent);
    }

    public static class UsbService extends Service {

        protected static final String LOG_TAG = "UsbService";

        private final ThreadPoolExecutor mThreadPoolExecutor;

        public UsbService() {
            super();
            mThreadPoolExecutor = new ThreadPoolExecutor(1, 1,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>());
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        public void onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate");

            super.onCreate();
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Logger.logDebug(LOG_TAG, "onStartCommand");

            String action = intent.getAction();
            if (action == null) {
                Logger.logError(LOG_TAG, "No action passed");
                ResultReturner.returnData(this, intent, out -> out.append("Missing action\n"));
            }

            if (action != null) {
                switch (action) {
                    case "list":
                        runListAction(intent);
                        break;
                    case "permission":
                        runPermissionAction(intent);
                        break;
                    case "info":
                        runInfoAction(intent);
                        break;
                    case "open":
                        runOpenAction(intent);
                        break;
                    default:
                        Logger.logError(LOG_TAG, "Invalid action: \"" + action + "\"");
                        ResultReturner.returnData(this, intent, out -> out.append("Invalid action: \"" + action + "\"\n"));
                }
            }

            return Service.START_NOT_STICKY;
        }

        @Override
        public void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");

            for (int i = 0; i < openDevices.size(); i++) {
                UsbDeviceConnection conn = openDevices.valueAt(i);
                if (conn != null) conn.close();
            }
            openDevices.clear();

            mThreadPoolExecutor.shutdown();

            super.onDestroy();
        }



        protected void runListAction(Intent intent) {
            Logger.logVerbose(LOG_TAG,"Running 'list' usb devices action");

            ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    listDevices(out);
                }
            });

            stopSelf();
        }

        protected void listDevices(JsonWriter out) throws IOException {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            out.beginArray();
            for (String deviceName : deviceList.keySet()) {
                out.value(deviceName);
            }
            out.endArray();
        }

        protected void runInfoAction(Intent intent) {
            Logger.logVerbose(LOG_TAG, "Running 'info' usb device action");

            ResultReturner.returnData(this, intent, new ResultReturner.ResultJsonWriter() {
                @Override
                public void writeJson(JsonWriter out) throws Exception {
                    String deviceName = intent.getStringExtra("device");
                    if (deviceName == null) {
                        out.beginObject();
                        out.name("error").value("No device specified");
                        out.endObject();
                        return;
                    }
                    deviceInfo(out, intent, deviceName);
                }
            });

            stopSelf();
        }

        protected void deviceInfo(JsonWriter out, Intent intent, String deviceName) throws IOException {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
            UsbDevice device = deviceList.get(deviceName);

            if (device == null) {
                out.beginObject();
                out.name("error").value("No such device: " + deviceName);
                out.endObject();
                return;
            }

            out.beginObject();
            out.name("deviceName").value(device.getDeviceName());
            out.name("deviceId").value(device.getDeviceId());
            out.name("vendorId").value("0x" + Integer.toHexString(device.getVendorId()).toUpperCase());
            out.name("productId").value("0x" + Integer.toHexString(device.getProductId()).toUpperCase());
            out.name("vendorIdInt").value(device.getVendorId());
            out.name("productIdInt").value(device.getProductId());
            out.name("deviceClass").value(deviceIdToName(device.getDeviceClass()));
            out.name("deviceClassCode").value(device.getDeviceClass());
            out.name("deviceSubclass").value(device.getDeviceSubclass());
            out.name("deviceProtocol").value(device.getDeviceProtocol());
            int interfaceCount = device.getInterfaceCount();
            out.name("interfaceCount").value(interfaceCount);
            out.name("interfaces");
            out.beginArray();
            for (int i = 0; i < interfaceCount; i++) {
                UsbInterface usbInterface = device.getInterface(i);
                out.beginObject();
                out.name("id").value(usbInterface.getId());
                out.name("class").value(deviceIdToName(usbInterface.getInterfaceClass()));
                out.name("classCode").value(usbInterface.getInterfaceClass());
                out.name("subclass").value(usbInterface.getInterfaceSubclass());
                out.name("protocol").value(usbInterface.getInterfaceProtocol());
                out.name("endpointCount").value(usbInterface.getEndpointCount());
                out.endObject();
            }
            out.endArray();
            boolean hasPermission = checkUsbDevicePermission(device);
            out.name("hasPermission").value(hasPermission);

            if (hasPermission) {
                String manufacturerName = device.getManufacturerName();
                String productName = device.getProductName();
                String serialNumber = device.getSerialNumber();

                if (manufacturerName != null) out.name("manufacturerName").value(manufacturerName);
                if (productName != null) out.name("productName").value(productName);
                if (serialNumber != null) out.name("serialNumber").value(serialNumber);
            }

            out.endObject();
        }

        /** Map USB class code to human-readable name. */
        private static String deviceIdToName(int usbClass) {
            switch (usbClass) {
                case 0x00: return "Per-interface";
                case 0x01: return "Audio";
                case 0x02: return "Communications";
                case 0x03: return "Human Interface Device (HID)";
                case 0x05: return "Physical";
                case 0x06: return "Image";
                case 0x07: return "Printer";
                case 0x08: return "Mass Storage";
                case 0x09: return "Hub";
                case 0x0A: return "CDC Data";
                case 0x0B: return "Smart Card";
                case 0x0D: return "Content Security";
                case 0x0E: return "Video";
                case 0x0F: return "Personal Healthcare";
                case 0x10: return "Audio/Video (AV)";
                case 0x11: return "Billboard";
                case 0x12: return "USB Type-C Bridge";
                case 0xDC: return "Diagnostic";
                case 0xE0: return "Wireless Controller";
                case 0xEF: return "Miscellaneous";
                case 0xFE: return "Application Specific";
                case 0xFF: return "Vendor Specific";
                default: return "Unknown (0x" + Integer.toHexString(usbClass).toUpperCase() + ")";
            }
        }


        protected void runPermissionAction(Intent intent) {
            mThreadPoolExecutor.submit(() -> {
                String deviceName = intent.getStringExtra("device");

                Logger.logVerbose(LOG_TAG,"Running 'permission' action for device \"" + deviceName + "\"");

                UsbDevice device = getDevice(intent, deviceName);
                if (device == null) {
                    stopSelf();
                    return;
                }

                int status = checkAndRequestUsbDevicePermission(intent, device);
                ResultReturner.returnData(this, intent, out -> {
                    if (status == 0) {
                        Logger.logVerbose(LOG_TAG, "Permission granted for device \"" + device.getDeviceName() + "\"");
                        out.append("Permission granted.\n" );
                    } else if (status == 1) {
                        Logger.logVerbose(LOG_TAG, "Permission denied for device \"" + device.getDeviceName() + "\"");
                        out.append("Permission denied.\n" );
                    } else if (status == -1) {
                        out.append("Permission request timeout.\n" );
                    }
                });
                stopSelf();
            });
        }



        protected void runOpenAction(Intent intent) {
            mThreadPoolExecutor.submit(() -> {
                String deviceName = intent.getStringExtra("device");

                Logger.logVerbose(LOG_TAG,"Running 'open' action for device \"" + deviceName + "\"");

                UsbDevice device = getDevice(intent, deviceName);
                if (device == null) return;

                int status = checkAndRequestUsbDevicePermission(intent, device);
                ResultReturner.returnData(this, intent, new ResultReturner.WithAncillaryFd() {
                    @Override
                    public void writeResult(PrintWriter out) {
                        if (status == 0) {
                            int fd = open(device);
                            if (fd < 0) {
                                String reason;
                                if (fd == -2) reason = "device busy or kernel driver claimed all interfaces";
                                else if (fd == -3) reason = "could not detach kernel driver";
                                else reason = "framework restricted raw fd access";
                                Logger.logVerbose(LOG_TAG, "Failed to open device \"" + device.getDeviceName()
                                        + "\": " + reason + " (code=" + fd + ")");
                                out.append("Open device failed.\n");
                            } else {
                                Logger.logVerbose(LOG_TAG, "Open device \"" + device.getDeviceName() + "\" successful");
                                this.sendFd(out, fd);
                            }
                        } else if (status == 1) {
                            Logger.logVerbose(LOG_TAG, "Permission denied to open device \"" + device.getDeviceName() + "\"");
                            out.append("Permission denied.\n" );
                        } else if (status == -1) {
                            out.append("Permission request timeout.\n" );
                        }
                    }
                });
            });
        }

        protected int open(@NonNull UsbDevice device) {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            String deviceName = device.getDeviceName();

            // Retry openDevice up to 3 times (race condition in USB service)
            UsbDeviceConnection connection = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                connection = usbManager.openDevice(device);
                if (connection != null) break;
                if (attempt == 0) {
                    Logger.logVerbose(LOG_TAG, "openDevice() returned null for device \"" + deviceName
                            + "\" — retrying... (attempt " + (attempt + 1) + "/3)");
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
            if (connection == null) {
                Logger.logVerbose(LOG_TAG, "openDevice() returned null for device \"" + deviceName
                        + "\" after 3 attempts — kernel driver may have claimed all interfaces");
                return -2;
            }

            int fd = connection.getFileDescriptor();
            if (fd >= 0) {
                Logger.logVerbose(LOG_TAG, "open() fd=" + fd + " for device \"" + deviceName + "\"");
                openDevices.put(fd, connection);
                return fd;
            }

            // fd == -1 — run diagnostics to determine root cause
            runFdDiagnostics(connection, device);

            // Try JNI fallback to extract fd from native object
            fd = nativeGetFd(connection);
            if (fd >= 0) {
                Logger.logVerbose(LOG_TAG, "JNI fallback got fd=" + fd + " for device \"" + deviceName + "\"");
                openDevices.put(fd, connection);
                return fd;
            }

            // Try detaching kernel drivers — this might fix fd on some vendor ROMs
            Logger.logVerbose(LOG_TAG, "getFileDescriptor() returned -1 for device \""
                    + deviceName + "\", trying kernel driver detach via claimInterface(force=true)...");

            boolean anyClaimed = detachKernelDrivers(connection, device);
            if (!anyClaimed) {
                connection.close();
                Logger.logVerbose(LOG_TAG, "Could not claim any interface on device \"" + deviceName + "\"");
                return -3;
            }

            fd = connection.getFileDescriptor();
            if (fd >= 0) {
                Logger.logVerbose(LOG_TAG, "getFileDescriptor() now returns " + fd
                        + " after kernel driver detach for device \"" + deviceName + "\"");
                releaseClaimedInterfaces(connection, device);
                openDevices.put(fd, connection);
                return fd;
            }

            // Try JNI again after detach
            fd = nativeGetFd(connection);
            if (fd >= 0) {
                Logger.logVerbose(LOG_TAG, "JNI fallback got fd=" + fd + " after detach for device \""
                        + deviceName + "\"");
                releaseClaimedInterfaces(connection, device);
                openDevices.put(fd, connection);
                return fd;
            }

            // Last resort: try to open device file directly
            fd = tryDirectDeviceOpen(device);
            if (fd >= 0) {
                Logger.logVerbose(LOG_TAG, "Direct open got fd=" + fd + " for device \"" + deviceName + "\"");
                openDevices.put(fd, connection);
                connection.close(); // Close the Java connection since we have raw fd now
                return fd;
            }

            Logger.logVerbose(LOG_TAG, "All methods failed to get fd for device \"" + deviceName + "\" —"
                    + " framework restricts raw fd access");
            connection.close();
            return -1;
        }

        /**
         * Run diagnostics when getFileDescriptor() returns -1.
         * Checks mNativeContext via reflection and verifies connection liveness.
         */
        private void runFdDiagnostics(UsbDeviceConnection connection, UsbDevice device) {
            try {
                String deviceName = device.getDeviceName();

                // Android version & device info
                Logger.logVerbose(LOG_TAG, "DIAG: Android=" + Build.VERSION.SDK_INT + " (" + Build.VERSION.CODENAME
                        + "), manufacturer=" + Build.MANUFACTURER + ", model=" + Build.MODEL);

                // Check mNativeContext via reflection
                Field contextField = UsbDeviceConnection.class.getDeclaredField("mNativeContext");
                contextField.setAccessible(true);
                long nativeContext = contextField.getLong(connection);
                Logger.logVerbose(LOG_TAG, "DIAG: mNativeContext=" + nativeContext + " for device \""
                        + deviceName + "\"");

                if (nativeContext == 0) {
                    Logger.logVerbose(LOG_TAG, "DIAG: mNativeContext=0 — connection was closed or never opened."
                            + " getSerial() would fail too.");
                } else {
                    Logger.logVerbose(LOG_TAG, "DIAG: mNativeContext != 0 — native usb_device exists."
                            + " Try JNI to extract fd directly.");
                    // Verify connection is alive
                    try {
                        String serial = connection.getSerial();
                        Logger.logVerbose(LOG_TAG, "DIAG: connection.getSerial()=" + serial + " — connection IS alive");
                    } catch (Exception e) {
                        Logger.logVerbose(LOG_TAG, "DIAG: connection.getSerial() threw: " + e.getMessage()
                                + " — connection may be dead");
                    }
                }
            } catch (NoSuchFieldException e) {
                Logger.logVerbose(LOG_TAG, "DIAG: mNativeContext field not found: " + e.getMessage());
            } catch (Exception e) {
                Logger.logVerbose(LOG_TAG, "DIAG: reflection error: " + e.getMessage());
            }
        }

        /**
         * JNI-based fd extraction: reads mNativeContext pointer and returns device->fd.
         * Falls back to reflection + direct fd read via libtermux-jni if available.
         */
        private int nativeGetFd(UsbDeviceConnection connection) {
            try {
                // Attempt 1: Use system.io.Os to read fd from a raw FileDescriptor
                Field contextField = UsbDeviceConnection.class.getDeclaredField("mNativeContext");
                contextField.setAccessible(true);
                long nativeContext = contextField.getLong(connection);
                if (nativeContext == 0) return -1;

                // Attempt 2: Try to load built-in JNI library if available
                try {
                    System.loadLibrary("termux-usb-fd");
                    return nativeReadFd(nativeContext);
                } catch (UnsatisfiedLinkError e) {
                    // JNI library not available — this is expected unless user compiled it
                }
            } catch (Exception e) {
                Logger.logVerbose(LOG_TAG, "nativeGetFd error: " + e.getMessage());
            }
            return -1;
        }

        /**
         * JNI stub — implemented in libtermux-usb-fd.so.
         * Reads fd from native usb_device struct at given pointer.
         */
        private static native int nativeReadFd(long nativeContext);

        /**
         * Last resort: try to open the device file directly via system call.
         */
        private int tryDirectDeviceOpen(UsbDevice device) {
            try {
                String deviceName = device.getDeviceName(); // "/dev/bus/usb/001/002"
                Logger.logVerbose(LOG_TAG, "Trying direct open of \"" + deviceName + "\"");
                // Use FileDescriptor + Os.open for direct device node access
                // Note: this will likely fail with EACCES on stock Android due to SELinux
                FileDescriptor fd = Os.open(deviceName,
                        android.system.OsConstants.O_RDWR, 0);
                if (fd != null && fd.valid()) {
                    int rawFd = Os.fcntlInt(fd,
                            android.system.OsConstants.F_DUPFD_CLOEXEC, 0);
                    if (rawFd >= 0) {
                        Os.close(fd);
                        return rawFd;
                    }
                }
            } catch (Exception e) {
                Logger.logVerbose(LOG_TAG, "Direct open failed: " + e.getMessage());
            }
            return -1;
        }

        /** Claim all interfaces with force=true to detach kernel drivers (UVC, UAC, etc.). */
        private boolean detachKernelDrivers(UsbDeviceConnection connection, UsbDevice device) {
            boolean anyClaimed = false;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                if (connection.claimInterface(intf, true)) {
                    Logger.logVerbose(LOG_TAG, "Claimed interface " + i + " with force=true — kernel driver detached");
                    anyClaimed = true;
                } else {
                    Logger.logVerbose(LOG_TAG, "Failed to claim interface " + i + " even with force=true");
                }
            }
            return anyClaimed;
        }

        /**
         * Release all claimed interfaces so the client (libusb) can claim them.
         * Android's releaseInterface() reconnects the kernel driver internally,
         * but libusb can auto-detach it again via USBDEVFS_DISCONNECT ioctl.
         */
        private void releaseClaimedInterfaces(UsbDeviceConnection connection, UsbDevice device) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                try {
                    connection.releaseInterface(device.getInterface(i));
                } catch (Exception ignored) { }
            }
        }



        protected UsbDevice getDevice(Intent intent, String deviceName) {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

            UsbDevice device = null;
            if (deviceName != null && !deviceName.isEmpty()) {
                device = deviceList.get(deviceName);
            } else {
                // `termux-usb <vendorId> <productId>` sends ids instead of a usbfs path. Those
                // extras used to be ignored completely, so every call answered "No such device".
                Integer vendorId = parseUsbId(intent.getStringExtra("vendorId"));
                Integer productId = parseUsbId(intent.getStringExtra("productId"));
                if (vendorId != null) {
                    List<UsbDevice> matches = new ArrayList<>();
                    for (UsbDevice candidate : deviceList.values()) {
                        if (candidate.getVendorId() != vendorId) continue;
                        if (productId != null && candidate.getProductId() != productId) continue;
                        matches.add(candidate);
                    }
                    if (matches.size() == 1) {
                        device = matches.get(0);
                    } else if (matches.size() > 1) {
                        // Picking one at random would silently talk to the wrong device.
                        StringBuilder message = new StringBuilder(
                                "More than one device matches " + intent.getStringExtra("vendorId"));
                        if (productId != null) message.append(":").append(intent.getStringExtra("productId"));
                        message.append(". Pass the device path instead:\n");
                        for (UsbDevice match : matches) {
                            message.append("  ").append(match.getDeviceName()).append("\n");
                        }
                        final String text = message.toString();
                        ResultReturner.returnData(this, intent, out -> out.append(text));
                        return null;
                    }
                }
            }

            if (device == null) {
                Logger.logVerbose(LOG_TAG, "Failed to find device \"" + deviceName + "\"");
                ResultReturner.returnData(this, intent, out -> out.append("No such device.\n"));
            }

            return device;
        }

        /** Accepts "18d1", "0x18d1" or "6353"; null when the value is not a usb id. */
        private Integer parseUsbId(String value) {
            if (value == null) return null;
            String v = value.trim();
            if (v.isEmpty()) return null;
            try {
                if (v.startsWith("0x") || v.startsWith("0X")) return Integer.parseInt(v.substring(2), 16);
                if (v.matches(".*[a-fA-F].*")) return Integer.parseInt(v, 16);
                return Integer.parseInt(v, 10);
            } catch (NumberFormatException e) {
                return null;
            }
        }



        protected boolean checkUsbDevicePermission(@NonNull UsbDevice device) {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            return usbManager.hasPermission(device);
        }

        protected int checkAndRequestUsbDevicePermission(Intent intent, @NonNull UsbDevice device) {
            boolean checkResult = checkUsbDevicePermission(device);
            Logger.logVerbose(LOG_TAG, "Permission check result for device \"" + device.getDeviceName() + "\": " + checkResult);
            if (checkResult) {
                return 0;
            }

            if(!intent.getBooleanExtra("request", false)) {
                return 1;
            }

            Logger.logVerbose(LOG_TAG, "Requesting permission for device \"" + device.getDeviceName() + "\"");

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Boolean> result = new AtomicReference<>();

            BroadcastReceiver usbReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent usbIntent) {
                    if (ACTION_USB_PERMISSION.equals(usbIntent.getAction())) {
                        boolean requestResult = usbIntent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                        Logger.logVerbose(LOG_TAG, "Permission request result for device \"" + device.getDeviceName() + "\": " + requestResult);
                        result.set(requestResult);
                    }
                    context.unregisterReceiver(this);
                    latch.countDown();
                }
            };

            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            Intent usbIntent = new Intent(ACTION_USB_PERMISSION);
            // Use explicit intent, otherwise permission request intent will be blocked if intent is
            // mutable and app uses `targetSdkVersion` `>= 34`, or following exception will be logged
            // to logcat if app uses `targetSdkVersion` `< 34`.
            // > `android.app.StackTrace: New mutable implicit PendingIntent: pkg=texmux.api,
            // > action=texmux.api.USB_PERMISSION, featureId=null. This will be blocked once the
            // > app targets U+ for security reasons.`
            // - https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
            usbIntent.setPackage(getPackageName());

            // Use mutable intent, otherwise permission request intent will be blocked if app
            // uses `targetSdkVersion` `>= 31` and following exception may be logged to logcat.
            // > java.lang.IllegalArgumentException: texmux.api: Targeting S+ (version 31 and above)
            // > requires that one of FLAG_IMMUTABLE or FLAG_MUTABLE be specified when creating a PendingIntent.
            // > Strongly consider using FLAG_IMMUTABLE, only use FLAG_MUTABLE if some functionality
            // > depends on the PendingIntent being mutable, e.g. if it needs to be used with inline
            // > replies or bubbles.
            // The intent must not be immutable as the `EXTRA_PERMISSION_GRANTED` extra needs to be
            // returned by the Android framework. Otherwise, if requesting permission after
            // reattaching device, and user presses `OK` to grant permission, the
            // `EXTRA_PERMISSION_GRANTED` extra would not exist in the intent, and default `false`
            // value would get used, and `No permission` condition of the open request would get
            // triggered, even though permission was granted and it won't need to be requested for
            // next open request.
            // - https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability
            //noinspection ObsoleteSdkInt
            int pendingIntentFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, usbIntent, pendingIntentFlags);

            try {
                // Specify flag to not export receiver, otherwise permission request intent will be
                // blocked if app uses `targetSdkVersion` `>= 34`.
                // - https://developer.android.com/about/versions/14/behavior-changes-14#runtime-receivers-exported
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION),
                            Context.RECEIVER_NOT_EXPORTED);
                } else {
                    //noinspection UnspecifiedRegisterReceiverFlag
                    registerReceiver(usbReceiver, new IntentFilter(ACTION_USB_PERMISSION));
                }

                // Request permission and wait.
                usbManager.requestPermission(device, permissionIntent);

                try {
                    if (!latch.await(30L, TimeUnit.SECONDS)) {
                        Logger.logVerbose(LOG_TAG, "Permission request time out for device \"" + device.getDeviceName() + "\" after 30s");
                        return -1;
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                Boolean requestResult = result.get();
                if (requestResult != null) {
                    usbReceiver = null;
                    return requestResult ? 0 : 1;
                } else {
                    return 1;
                }
            } finally {
                try {
                    if (usbReceiver != null) {
                        unregisterReceiver(usbReceiver);
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

}
