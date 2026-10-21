package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.JsonWriter;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exposing {@link NsdManager} — mDNS/DNS-SD service discovery and advertisement.
 *
 * <p>Discovery is asynchronous: {@code discoverServices()} only registers a
 * listener, results arrive later. Each command therefore blocks its own handler
 * thread on a latch with a bounded timeout, which is what the caller wants —
 * {@code termux-nsd discover} should print the services it found, not return an
 * empty list the moment it is asked.
 */
public class NsdAPI {

    private static final String LOG_TAG = "NsdAPI";

    /**
     * Registrations kept alive so that {@code termux-nsd unregister} can drop
     * them. NsdManager only unregisters through the same listener instance that
     * registered, so it has to be held somewhere.
     */
    private static final Map<String, NsdManager.RegistrationListener> registrations =
        new ConcurrentHashMap<>();

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        ResultReturner.returnData(apiReceiver, intent, new ResultReturner.ResultJsonWriter() {
            @Override
            public void writeJson(JsonWriter out) throws Exception {
                String command = intent.getStringExtra("command");
                if (command == null || command.isEmpty()) command = "status";

                NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
                if (manager == null) {
                    error(out, "NsdManager unavailable on this device");
                    return;
                }

                switch (command) {
                    case "discover":
                        discover(out, manager, intent);
                        break;
                    case "register":
                        register(out, manager, intent);
                        break;
                    case "unregister":
                        unregister(out, manager, intent);
                        break;
                    case "status":
                        status(out);
                        break;
                    default:
                        error(out, "Unknown command: " + command);
                        break;
                }
            }
        });
    }

    private static void discover(JsonWriter out, NsdManager manager, Intent intent) throws Exception {
        String serviceType = intent.getStringExtra("service_type");
        if (serviceType == null || serviceType.isEmpty()) {
            error(out, "Missing 'service_type' (for example _http._tcp.)");
            return;
        }
        int timeout = intent.getIntExtra("timeout", 5);
        if (timeout <= 0) timeout = 5;
        if (timeout > 60) timeout = 60;

        final List<NsdServiceInfo> found = Collections.synchronizedList(new ArrayList<NsdServiceInfo>());
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                failure.set("Discovery could not start (" + errorCode + ") for " + type);
                started.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
                Logger.logWarn(LOG_TAG, "stopDiscovery failed (" + errorCode + ") for " + type);
            }

            @Override
            public void onDiscoveryStarted(String type) {
                started.countDown();
            }

            @Override
            public void onDiscoveryStopped(String type) {
                // nothing to release: the listener is not held beyond this call
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (found) {
                    for (NsdServiceInfo existing : found) {
                        if (existing.getServiceName().equals(info.getServiceName())
                            && existing.getServiceType().equals(info.getServiceType())) return;
                    }
                    found.add(info);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                synchronized (found) {
                    for (int i = found.size() - 1; i >= 0; i--) {
                        NsdServiceInfo existing = found.get(i);
                        if (existing.getServiceName().equals(info.getServiceName())
                            && existing.getServiceType().equals(info.getServiceType())) {
                            found.remove(i);
                        }
                    }
                }
            }
        };

        long deadline = System.currentTimeMillis() + timeout * 1000L;
        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (IllegalArgumentException e) {
            error(out, "Discovery rejected: " + e.getMessage());
            return;
        }

        // Wait for the platform to confirm the start, then keep listening for the
        // rest of the requested window.
        started.await(Math.min(timeout, 5), TimeUnit.SECONDS);
        long left = deadline - System.currentTimeMillis();
        if (left > 0) {
            try {
                Thread.sleep(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            manager.stopServiceDiscovery(listener);
        } catch (IllegalArgumentException e) {
            // Not started (the failure callback already recorded why).
            Logger.logWarn(LOG_TAG, "stopDiscovery rejected: " + e.getMessage());
        }

        if (failure.get() != null) {
            error(out, failure.get());
            return;
        }

        List<NsdServiceInfo> snapshot;
        synchronized (found) {
            snapshot = new ArrayList<>(found);
        }

        out.beginObject();
        out.name("service_type").value(serviceType);
        out.name("timeout").value(timeout);
        out.name("count").value(snapshot.size());
        out.name("services");
        out.beginArray();
        for (NsdServiceInfo info : snapshot) {
            out.beginObject();
            out.name("service_name").value(info.getServiceName());
            out.name("service_type").value(info.getServiceType());
            // Discovery only carries the name and type; address and port need a
            // separate resolve round, one at a time (concurrent resolves fail).
            NsdServiceInfo resolved = resolve(manager, info);
            if (resolved != null && resolved.getHost() != null) {
                out.name("host").value(resolved.getHost().getHostAddress());
                out.name("port").value(resolved.getPort());
            } else {
                out.name("resolved").value(false);
                if (resolved != null) out.name("port").value(resolved.getPort());
            }
            out.endObject();
        }
        out.endArray();
        out.endObject();
    }

    /** Resolve one discovered service, waiting up to 3 s for the answer. */
    private static NsdServiceInfo resolve(NsdManager manager, NsdServiceInfo info) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<NsdServiceInfo> result = new AtomicReference<>();

        NsdManager.ResolveListener listener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Logger.logWarn(LOG_TAG, "resolve failed (" + errorCode + ") for "
                    + serviceInfo.getServiceName());
                done.countDown();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                result.set(serviceInfo);
                done.countDown();
            }
        };

        try {
            manager.resolveService(info, listener);
        } catch (IllegalArgumentException e) {
            Logger.logWarn(LOG_TAG, "resolve rejected: " + e.getMessage());
            return null;
        }

        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static void register(JsonWriter out, NsdManager manager, Intent intent) throws Exception {
        String name = intent.getStringExtra("service_name");
        String serviceType = intent.getStringExtra("service_type");
        int port = intent.getIntExtra("port", 0);

        if (name == null || name.isEmpty()) {
            error(out, "Missing 'service_name'");
            return;
        }
        if (serviceType == null || serviceType.isEmpty()) {
            error(out, "Missing 'service_type' (for example _ssh._tcp.)");
            return;
        }
        if (port <= 0 || port > 65535) {
            error(out, "Missing or invalid 'port'");
            return;
        }

        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();
        final String key = name + "|" + serviceType;

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(serviceType);
        info.setPort(port);

        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                failure.set("Registration failed (" + errorCode + ") for " + serviceInfo.getServiceName());
                done.countDown();
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Logger.logWarn(LOG_TAG, "unregistration failed (" + errorCode + ") for "
                    + serviceInfo.getServiceName());
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                done.countDown();
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                registrations.remove(key);
            }
        };

        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (IllegalArgumentException e) {
            error(out, "Registration rejected: " + e.getMessage());
            return;
        }

        done.await(5, TimeUnit.SECONDS);
        if (failure.get() != null) {
            error(out, failure.get());
            return;
        }
        registrations.put(key, listener);

        out.beginObject();
        out.name("registered").value(true);
        out.name("service_name").value(name);
        out.name("service_type").value(serviceType);
        out.name("port").value(port);
        out.endObject();
    }

    private static void unregister(JsonWriter out, NsdManager manager, Intent intent) throws Exception {
        String name = intent.getStringExtra("service_name");
        String serviceType = intent.getStringExtra("service_type");
        boolean everything = name == null || name.isEmpty();

        int dropped = 0;
        for (Iterator<Map.Entry<String, NsdManager.RegistrationListener>> it =
                 registrations.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, NsdManager.RegistrationListener> entry = it.next();
            int split = entry.getKey().indexOf('|');
            String entryName = split < 0 ? entry.getKey() : entry.getKey().substring(0, split);
            String entryType = split < 0 ? "" : entry.getKey().substring(split + 1);

            boolean match = everything
                || (entryName.equals(name) && (serviceType == null || entryType.equals(serviceType)));
            if (!match) continue;

            try {
                manager.unregisterService(entry.getValue());
                dropped++;
            } catch (IllegalArgumentException e) {
                Logger.logWarn(LOG_TAG, "unregister rejected: " + e.getMessage());
            }
            it.remove();
        }

        out.beginObject();
        out.name("unregistered").value(dropped);
        out.name("remaining").value(registrations.size());
        out.endObject();
    }

    private static void status(JsonWriter out) throws Exception {
        out.beginObject();
        out.name("available").value(true);
        out.name("protocol").value("PROTOCOL_DNS_SD");
        out.name("active_registrations").value(registrations.size());
        out.name("registrations");
        out.beginArray();
        for (String key : registrations.keySet()) {
            int split = key.indexOf('|');
            out.beginObject();
            out.name("service_name").value(split < 0 ? key : key.substring(0, split));
            if (split >= 0) out.name("service_type").value(key.substring(split + 1));
            out.endObject();
        }
        out.endArray();
        out.endObject();
    }

    private static void error(JsonWriter out, String message) throws Exception {
        out.beginObject().name("API_ERROR").value(message).endObject();
    }
}
