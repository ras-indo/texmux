package com.termux.api.apis;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.JsonWriter;

import androidx.annotation.RequiresPermission;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.activities.TermuxApiPermissionActivity;
import com.termux.api.util.ResultReturner;
import com.termux.api.util.ResultReturner.ResultJsonWriter;
import com.termux.shared.logger.Logger;

import java.io.IOException;

public class LocationAPI {

    private static final String LOG_TAG = "LocationAPI";

    private static final String REQUEST_LAST_KNOWN = "last";
    private static final String REQUEST_ONCE = "once";
    private static final String REQUEST_UPDATES = "updates";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        // Queue the location switch page so it opens right after the permission
        // dialog is answered, instead of on a separate command run.
        intent.putExtra(TermuxApiPermissionActivity.SYSTEM_ACTION_EXTRA,
                Settings.ACTION_LOCATION_SOURCE_SETTINGS);

        if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                Manifest.permission.ACCESS_FINE_LOCATION)) return;

        // The permission alone is not enough: with the location toggle off every
        // provider answers null. There is no dialog for that switch, so open the
        // system page that owns it.
        LocationManager locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            boolean locationOff = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? !locationManager.isLocationEnabled()
                    : !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (locationOff) {
                ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
                    @Override
                    public void writeJson(JsonWriter out) throws Exception {
                        out.beginObject()
                                .name("error").value("Location services are off")
                                .name("hint").value("Nyalakan Location di layar yang muncul, lalu jalankan ulang perintahnya.")
                                .endObject();
                    }
                });
                TermuxApiPermissionActivity.requestSystemAction(
                        context, Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                return;
            }
        }

        ResultReturner.returnData(apiReceiver, intent, new ResultJsonWriter() {
            @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            @Override
            public void writeJson(final JsonWriter out) throws Exception {
                LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                if (manager == null) {
                    // Without this the NPE escapes writeJson, the socket is never
                    // written and the command looks frozen instead of failing.
                    out.beginObject().name("API_ERROR").value("LocationManager unavailable").endObject();
                    return;
                }

                String provider = intent.getStringExtra("provider");
                if (provider == null)
                    provider = LocationManager.GPS_PROVIDER;
                if (!(provider.equals(LocationManager.GPS_PROVIDER) || provider.equals(LocationManager.NETWORK_PROVIDER) || provider
                        .equals(LocationManager.PASSIVE_PROVIDER))) {
                    out.beginObject()
                            .name("API_ERROR")
                            .value("Unsupported provider '" + provider + "' - only '" + LocationManager.GPS_PROVIDER + "', '"
                                    + LocationManager.NETWORK_PROVIDER + "' and '" + LocationManager.PASSIVE_PROVIDER + "' supported").endObject();
                    return;
                }

                String request = intent.getStringExtra("request");
                if (request == null)
                    request = REQUEST_ONCE;
                switch (request) {
                    case REQUEST_LAST_KNOWN:
                        Location lastKnownLocation = manager.getLastKnownLocation(provider);
                        locationToJson(lastKnownLocation, out);
                        break;
                    case REQUEST_ONCE:
                        // requestSingleUpdate() only calls back when a fix
                        // arrives. With the provider disabled it never does, and
                        // the command sat on the socket forever printing nothing.
                        if (!manager.isProviderEnabled(provider)) {
                            out.beginObject()
                                    .name("API_ERROR").value("Provider '" + provider + "' is disabled")
                                    .name("hint").value("Nyalakan Location/GPS lalu jalankan ulang perintahnya.")
                                    .endObject();
                            break;
                        }

                        Looper.prepare();
                        final boolean[] answered = {false};
                        final Handler timeoutHandler = new Handler();
                        timeoutHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (answered[0]) return;
                                try {
                                    out.beginObject()
                                            .name("API_ERROR").value("Location timeout after 20s")
                                            .name("hint").value("Belum ada fix. Coba di luar ruangan, atau pakai -r last / -p network.")
                                            .endObject();
                                } catch (IOException e) {
                                    Logger.logStackTraceWithMessage(LOG_TAG, "Writing timeout", e);
                                } finally {
                                    Looper.myLooper().quit();
                                }
                            }
                        }, 20 * 1000);
                        manager.requestSingleUpdate(provider, new LocationListener() {

                            @Override
                            public void onStatusChanged(String changedProvider, int status, Bundle extras) {
                                // TODO Auto-generated method stub
                            }

                            @Override
                            public void onProviderEnabled(String changedProvider) {
                                // TODO Auto-generated method stub
                            }

                            @Override
                            public void onProviderDisabled(String changedProvider) {
                                // TODO Auto-generated method stub
                            }

                            @Override
                            public void onLocationChanged(Location location) {
                                try {
                                    answered[0] = true;
                                    timeoutHandler.removeCallbacksAndMessages(null);
                                    locationToJson(location, out);
                                } catch (IOException e) {
                                    Logger.logStackTraceWithMessage(LOG_TAG, "Writing json", e);
                                } finally {
                                    Looper.myLooper().quit();
                                }
                            }
                        }, null);
                        Looper.loop();
                        break;
                    case REQUEST_UPDATES:
                        Looper.prepare();
                        manager.requestLocationUpdates(provider, 5000, 50.f, new LocationListener() {

                            @Override
                            public void onStatusChanged(String changedProvider, int status, Bundle extras) {
                                // Do nothing.
                            }

                            @Override
                            public void onProviderEnabled(String changedProvider) {
                                // Do nothing.
                            }

                            @Override
                            public void onProviderDisabled(String changedProvider) {
                                // Do nothing.
                            }

                            @Override
                            public void onLocationChanged(Location location) {
                                try {
                                    locationToJson(location, out);
                                    out.flush();
                                } catch (IOException e) {
                                    Logger.logStackTraceWithMessage(LOG_TAG, "Writing json", e);
                                }
                            }
                        }, null);
                        final Looper looper = Looper.myLooper();
                        new Thread() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(30 * 1000);
                                } catch (InterruptedException e) {
                                    Logger.logStackTraceWithMessage(LOG_TAG, "INTER", e);
                                }
                                looper.quit();
                            }
                        }.start();
                        Looper.loop();
                        break;
                    default:
                        out.beginObject()
                                .name("API_ERROR")
                                .value("Unsupported request '" + request + "' - only '" + REQUEST_LAST_KNOWN + "', '" + REQUEST_ONCE + "' and '" + REQUEST_UPDATES
                                        + "' supported").endObject();
                }
            }
        });
    }

    static void locationToJson(Location lastKnownLocation, JsonWriter out) throws IOException {
        if (lastKnownLocation == null) {
            out.beginObject().name("API_ERROR").value("Failed to get location").endObject();
            return;
        }
        out.beginObject();
        out.name("latitude").value(lastKnownLocation.getLatitude());
        out.name("longitude").value(lastKnownLocation.getLongitude());
        out.name("altitude").value(lastKnownLocation.getAltitude());
        out.name("accuracy").value(lastKnownLocation.getAccuracy());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            out.name("vertical_accuracy").value(lastKnownLocation.getVerticalAccuracyMeters());
        }
        out.name("bearing").value(lastKnownLocation.getBearing());
        out.name("speed").value(lastKnownLocation.getSpeed());
        long elapsedMs = (SystemClock.elapsedRealtimeNanos() - lastKnownLocation.getElapsedRealtimeNanos()) / 1000000;
        out.name("elapsedMs").value(elapsedMs);
        out.name("provider").value(lastKnownLocation.getProvider());
        out.endObject();
    }
}
