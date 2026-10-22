package com.termux.api.apis;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.IBinder;

import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.Semaphore;


/**
 * API that allows you to listen to all sensors on device
 */
public class SensorAPI {

    private static final String LOG_TAG = "SensorAPI";

    /**
     * Returns the permission set required for the sensor request carried by
     * {@code intent}. Only the permissions the requested sensor types actually
     * need are returned, so requesting the accelerometer never asks for
     * BODY_SENSORS or ACTIVITY_RECOGNITION.
     *
     * <ul>
     *   <li>{@code TYPE_HEART_BEAT} needs {@code BODY_SENSORS}</li>
     *   <li>{@code TYPE_STEP_DETECTOR} / {@code TYPE_STEP_COUNTER} need
     *       {@code ACTIVITY_RECOGNITION} (declared in the manifest)</li>
     *   <li>everything else needs no runtime permission</li>
     * </ul>
     */
    public static String[] requiredPermissions(Context context, Intent intent) {
        java.util.Set<String> perms = new java.util.HashSet<>();
        String[] requestedSensors = getUserRequestedSensors(intent);
        for (String sensorName : requestedSensors) {
            if (sensorName == null) continue;
            String normalized = sensorName.trim().toUpperCase(java.util.Locale.US)
                    .replace("_", "").replace(" ", "");
            Integer type = SENSOR_TYPE_NAME_MAP.get(normalized);
            if (type == null) continue;
            if (type == Sensor.TYPE_HEART_BEAT) {
                perms.add(Manifest.permission.BODY_SENSORS);
            } else if (type == Sensor.TYPE_STEP_DETECTOR
                    || type == Sensor.TYPE_STEP_COUNTER) {
                perms.add(Manifest.permission.ACTIVITY_RECOGNITION);
            }
        }
        // -a "all" may include body/step sensors, so request what the device has.
        if (intent.getBooleanExtra("all", false)) {
            SensorManager sensorManager = getSensorManager(context);
            if (sensorManager != null) {
                for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
                    if (sensor.getType() == Sensor.TYPE_HEART_BEAT) {
                        perms.add(Manifest.permission.BODY_SENSORS);
                    } else if (sensor.getType() == Sensor.TYPE_STEP_DETECTOR
                            || sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
                        perms.add(Manifest.permission.ACTIVITY_RECOGNITION);
                    }
                }
            }
        }
        return perms.toArray(new String[0]);
    }

    /**
     * Starts our SensorReader service
     */
    public static void onReceive(final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        Intent serviceIntent = new Intent(context, SensorReaderService.class);
        serviceIntent.setAction(intent.getAction());
        Bundle extras = intent.getExtras();
        if (extras != null)
            serviceIntent.putExtras(extras);
        context.startService(serviceIntent);
    }


    /**
     * All sensor listening functionality exists in this background service
     */
    public static class SensorReaderService extends Service {

        // indentation for JSON output
        protected static final int INDENTATION = 2;

        protected static SensorManager sensorManager;
        protected static JSONObject sensorReadout;
        protected static SensorOutputWriter outputWriter;

        // prevent concurrent modifications w/ sensor readout
        protected static Semaphore semaphore;

        /**
         * Map from normalized sensor type name (uppercase, no underscores, no spaces)
         * to the corresponding Sensor.TYPE_* integer constant.
         * Used for resolving composite sensors by their standard type name.
         */
        protected static final java.util.Map<String, Integer> SENSOR_TYPE_NAME_MAP;
        static {
            java.util.Map<String, Integer> map = new java.util.HashMap<>();
            map.put("ACCELEROMETER", Sensor.TYPE_ACCELEROMETER);
            map.put("MAGNETICFIELD", Sensor.TYPE_MAGNETIC_FIELD);
            map.put("ORIENTATION", Sensor.TYPE_ORIENTATION);
            map.put("GYROSCOPE", Sensor.TYPE_GYROSCOPE);
            map.put("LIGHT", Sensor.TYPE_LIGHT);
            map.put("PRESSURE", Sensor.TYPE_PRESSURE);
            map.put("TEMPERATURE", Sensor.TYPE_TEMPERATURE);
            map.put("PROXIMITY", Sensor.TYPE_PROXIMITY);
            map.put("GRAVITY", Sensor.TYPE_GRAVITY);
            map.put("LINEARACCELERATION", Sensor.TYPE_LINEAR_ACCELERATION);
            map.put("ROTATIONVECTOR", Sensor.TYPE_ROTATION_VECTOR);
            map.put("RELATIVEHUMIDITY", Sensor.TYPE_RELATIVE_HUMIDITY);
            map.put("AMBIENTTEMPERATURE", Sensor.TYPE_AMBIENT_TEMPERATURE);
            map.put("MAGNETICFIELDUNCALIBRATED", Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
            map.put("GAMEROTATIONVECTOR", Sensor.TYPE_GAME_ROTATION_VECTOR);
            map.put("GYROSCOPEUNCALIBRATED", Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
            map.put("SIGNIFICANTMOTION", Sensor.TYPE_SIGNIFICANT_MOTION);
            map.put("STEPDETECTOR", Sensor.TYPE_STEP_DETECTOR);
            map.put("STEPCOUNTER", Sensor.TYPE_STEP_COUNTER);
            map.put("GEOMAGNETICROTATIONVECTOR", Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
            map.put("HEARTBEAT", Sensor.TYPE_HEART_BEAT);
            SENSOR_TYPE_NAME_MAP = java.util.Collections.unmodifiableMap(map);
        }

        private static final String LOG_TAG = "SensorReaderService";

        public void onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate");

            super.onCreate();
            sensorReadout = new JSONObject();
            semaphore = new Semaphore(1);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Logger.logDebug(LOG_TAG, "onStartCommand");

            String command = intent.getAction();
            Context context = getApplicationContext();
            SensorManager sensorManager = getSensorManager(context);

            SensorCommandHandler handler = getSensorCommandHandler(command);
            SensorCommandResult result = handler.handle(sensorManager, context, intent);

            if (result.type == ResultType.SINGLE) {
                // post one-time result now, rather than an active stream
                postSensorCommandResult(context, intent, result);
            }
            return Service.START_NOT_STICKY;
        }

        protected static SensorManager getSensorManager(Context context) {
            if (sensorManager == null) {
                sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            }
            return sensorManager;
        }

        @Override
        public void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");

            super.onDestroy();
            cleanup();
        }

        protected static void cleanup() {
            if (outputWriter != null && outputWriter.isRunning()) {
                outputWriter.interrupt();
                outputWriter = null;
            }

            if (sensorManager != null) {
                sensorManager.unregisterListener(sensorEventListener);
                sensorManager = null;
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        /**
         * Sensor event listener for reading sensor value updates and storing them
         * in the sensorReadout JSON object
         */
        protected static SensorEventListener sensorEventListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent sensorEvent) {
                JSONArray sensorValuesArray = new JSONArray();
                try {
                    semaphore.acquire();
                    for (int j = 0; j < sensorEvent.values.length; ++j) {
                        sensorValuesArray.put(j, sensorEvent.values[j]);
                    }
                    JSONObject sensorInfo = new JSONObject();
                    sensorInfo.put("values", sensorValuesArray);
                    sensorInfo.put("timestamp", sensorEvent.timestamp);
                    sensorInfo.put("accuracy", sensorEvent.accuracy);
                    sensorReadout.put(sensorEvent.sensor.getName(), sensorInfo);
                    semaphore.release();
                } catch (JSONException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "onSensorChanged error", e);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                try {
                    semaphore.acquire();
                    String sensorName = sensor.getName();
                    JSONObject existing = sensorReadout.optJSONObject(sensorName);
                    if (existing != null) {
                        existing.put("accuracy", accuracy);
                    } else {
                        JSONObject info = new JSONObject();
                        info.put("accuracy", accuracy);
                        sensorReadout.put(sensorName, info);
                    }
                    Logger.logDebug(LOG_TAG, "Accuracy changed for " + sensorName + ": " + accuracy);
                    semaphore.release();
                } catch (JSONException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "onAccuracyChanged error", e);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        };

        protected static SensorCommandHandler getSensorCommandHandler(final String command) {
            switch (command == null ? "" : command) {
                case "list":
                    return listHandler;
                case "cleanup":
                    return cleanupHandler;
                case "sensors":
                    return sensorHandler;
                default:
                    return (sensorManager, context, intent) -> {
                        SensorCommandResult result = new SensorCommandResult();
                        result.message = "Unknown command: " + command;
                        return result;
                    };
            }
        }

        private void postSensorCommandResult(final Context context, final Intent intent,
                                             final SensorCommandResult result) {

            ResultReturner.returnData(context, intent, out -> {
                out.append(result.message).append("\n");
                if (result.error != null) {
                    out.append(result.error).append("\n");
                }
                out.flush();
                out.close();
            });
        }


        /*
         * -----
         * Sensor Command Handlers
         * -----
         */


        /**
         * Handler for returning a list of all available sensors
         */
        static SensorCommandHandler listHandler = (sensorManager, context, intent) -> {
            SensorCommandResult result = new SensorCommandResult();
            JSONArray sensorArray = new JSONArray();
            List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL);

            try {
                for (int j = 0; j < sensorList.size(); ++j) {
                    Sensor sensor = sensorList.get(j);
                    JSONObject sensorInfo = new JSONObject();
                    sensorInfo.put("name", sensor.getName());
                    sensorInfo.put("vendor", sensor.getVendor());
                    sensorInfo.put("version", sensor.getVersion());
                    sensorInfo.put("type", sensor.getType());
                    sensorInfo.put("power_mA", sensor.getPower());
                    sensorInfo.put("resolution", sensor.getResolution());
                    sensorInfo.put("max_range", sensor.getMaximumRange());
                    sensorInfo.put("min_delay_us", sensor.getMinDelay());
                    sensorArray.put(sensorInfo);
                }
                JSONObject output = new JSONObject();
                output.put("sensors", sensorArray);
                result.message = output.toString(INDENTATION);
            } catch (JSONException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "listHandler JSON error", e);
            }
            return result;
        };

        /**
         * Handler for managing cleaning up sensor resources
         */
        static SensorCommandHandler cleanupHandler = new SensorCommandHandler() {
            @Override
            public SensorCommandResult handle(SensorManager sensorManager, Context context, Intent intent) {
                SensorCommandResult result = new SensorCommandResult();

                if (outputWriter != null) {
                    outputWriter.interrupt();
                    outputWriter = null;
                    sensorManager.unregisterListener(sensorEventListener);
                    result.message = "Sensor cleanup successful!";
                    Logger.logInfo(LOG_TAG, "Cleanup()");
                } else {
                    result.message = "Sensor cleanup unnecessary";
                }
                return result;
            }
        };

        /**
         * Handler for managing listening to sensors
         */
        static SensorCommandHandler sensorHandler = new SensorCommandHandler() {
            @Override
            public SensorCommandResult handle(SensorManager sensorManager, Context context, Intent intent) {
                SensorCommandResult result = new SensorCommandResult();
                result.type = ResultType.CONTINUOUS;

                clearSensorValues();

                // sensor list user passed to us
                String[] requestedSensors = getUserRequestedSensors(intent);
                List<Sensor> sensorsToListenTo = getSensorsToListenTo(sensorManager, requestedSensors, intent);

                if (sensorsToListenTo.isEmpty()) {
                    result.message = "No valid sensors were registered!";
                    result.type = ResultType.SINGLE;
                } else {
                    if (outputWriter == null) {
                        outputWriter = createSensorOutputWriter(intent);
                        outputWriter.start();
                    }
                }
                return result;
            }
        };

        /**
         * Gets a string array of all user requested sensor names to listen to
         */
        protected static String[] getUserRequestedSensors(Intent intent) {
            // sensor values passed to us from user
            String sensorListString = intent.hasExtra("sensors") ? intent.getStringExtra("sensors") : "";
            return sensorListString.split(",");
        }

        /**
         * Gets a list of all sensors to listen to, that were requested and are available
         */
        protected static List<Sensor> getSensorsToListenTo(SensorManager sensorManager, String[] requestedSensors, Intent intent) {
            List<Sensor> availableSensors = new ArrayList<>(sensorManager.getSensorList(Sensor.TYPE_ALL));
            Collections.sort(availableSensors, (s1, s2) -> s1.getName().compareTo(s2.getName()));
            List<Sensor> sensorsToListenTo = new ArrayList<>();

            boolean listenToAll = intent.getBooleanExtra("all", false);
            String delayMode = intent.hasExtra("delay_mode") ? intent.getStringExtra("delay_mode") : null;
            final int sensorDelay = parseDelayMode(delayMode);

            if (listenToAll) {
                for (Sensor sensor : availableSensors) {
                    sensorManager.registerListener(sensorEventListener, sensor, sensorDelay);
                }
                sensorsToListenTo = availableSensors;
                Logger.logInfo(LOG_TAG, "Listening to ALL sensors with delay_mode: "
                        + (delayMode != null ? delayMode : "ui"));
            } else {

                // try to find matching sensors that were sent in request
                for (String requestedSensor : requestedSensors) {
                    if (requestedSensor == null || requestedSensor.trim().isEmpty()) continue;
                    String trimmedRequest = requestedSensor.trim();

                    // First: try name-based matching (existing behavior)
                    Sensor shortestMatchSensor = null;
                    int shortestMatchSensorLength = Integer.MAX_VALUE;

                    for (Sensor availableSensor : availableSensors) {
                        String sensorName = availableSensor.getName().toUpperCase();
                        if (sensorName.contains(trimmedRequest.toUpperCase()) && sensorName.length() < shortestMatchSensorLength) {
                            shortestMatchSensor = availableSensor;
                            shortestMatchSensorLength = sensorName.length();
                        }
                    }

                    // Second: if name-based failed, try type-name-based lookup
                    // This ensures composite sensors (TYPE_ROTATION_VECTOR, TYPE_GRAVITY,
                    // TYPE_LINEAR_ACCELERATION) work even if the device vendor names differ
                    if (shortestMatchSensor == null) {
                        shortestMatchSensor = findSensorByTypeName(trimmedRequest, sensorManager);
                        if (shortestMatchSensor != null) {
                            Logger.logInfo(LOG_TAG, "Resolved sensor '" + trimmedRequest
                                    + "' by type to: " + shortestMatchSensor.getName());
                        }
                    }

                    if (shortestMatchSensor != null) {
                        sensorManager.registerListener(sensorEventListener, shortestMatchSensor, sensorDelay);
                        sensorsToListenTo.add(shortestMatchSensor);
                    } else {
                        Logger.logInfo(LOG_TAG, "Sensor not found: '" + trimmedRequest + "'");
                    }
                }
            }
            return sensorsToListenTo;
        }

        /**
         * Clears out sensorEventListener as well as our sensorReadout JSON object
         */
        protected static void clearSensorValues() {
            // prevent duplicate listeners
            sensorManager.unregisterListener(sensorEventListener);

            // clear out old values
            sensorReadout = new JSONObject();
        }

        /**
         * Attempts to find a sensor by its type name.
         * This handles standard sensor type names like "ACCELEROMETER", "GRAVITY",
         * "LINEAR_ACCELERATION", "ROTATION_VECTOR" — which may not match
         * device-specific sensor naming conventions via getName().
         *
         * @param typeName normalized type name (e.g. "gravity", "rotation_vector")
         * @param sensorManager the device SensorManager
         * @return the first matching Sensor, or null if no match
         */
        protected static Sensor findSensorByTypeName(String typeName, SensorManager sensorManager) {
            // Normalize: uppercase, strip underscores and spaces
            String normalized = typeName.toUpperCase(java.util.Locale.US)
                    .replace("_", "")
                    .replace(" ", "");
            Integer sensorType = SENSOR_TYPE_NAME_MAP.get(normalized);
            if (sensorType != null) {
                List<Sensor> sensors = sensorManager.getSensorList(sensorType);
                if (!sensors.isEmpty()) {
                    return sensors.get(0);
                }
            }
            return null;
        }

        /**
         * Parses a delay_mode string extra into a SensorManager.SENSOR_DELAY_* constant.
         *
         * @param delayMode the mode string ("ui", "game", "fastest", "normal", or null)
         * @return the corresponding SensorManager delay constant
         */
        protected static int parseDelayMode(String delayMode) {
            if (delayMode == null) return SensorManager.SENSOR_DELAY_UI;
            switch (delayMode.toLowerCase(java.util.Locale.US)) {
                case "game":
                    return SensorManager.SENSOR_DELAY_GAME;
                case "fastest":
                    return SensorManager.SENSOR_DELAY_FASTEST;
                case "normal":
                    return SensorManager.SENSOR_DELAY_NORMAL;
                case "ui":
                default:
                    return SensorManager.SENSOR_DELAY_UI;
            }
        }

        /**
         * Creates SensorOutputWriter to write sensor values to stdout
         */
        protected static SensorOutputWriter createSensorOutputWriter(Intent intent) {
            String socketAddress = intent.getStringExtra("socket_output");

            outputWriter = new SensorOutputWriter(socketAddress);
            outputWriter.setOnErrorListener(e -> {
                outputWriter = null;
                Logger.logStackTraceWithMessage(LOG_TAG, "SensorOutputWriter error", e);
            });

            int delay = intent.getIntExtra("delay", SensorOutputWriter.DEFAULT_DELAY);
            Logger.logInfo(LOG_TAG, "Delay set to: " + delay);
            outputWriter.setDelay(delay);

            int limit = intent.getIntExtra("limit", SensorOutputWriter.DEFAULT_LIMIT);
            Logger.logInfo(LOG_TAG, "SensorOutput limit set to: " + limit);
            outputWriter.setLimit(limit);

            return outputWriter;
        }


        /**
         * Handles continuously writing Sensor info to an OutputStream asynchronously
         */
        static class SensorOutputWriter extends Thread {
            // delay in milliseconds before posting new sensor reading
            static final int DEFAULT_DELAY = 1000;

            static final int DEFAULT_LIMIT = Integer.MAX_VALUE;

            protected String outputSocketAddress;
            protected boolean isRunning;
            protected int delay;
            protected int counter;
            protected int limit;
            protected SocketWriterErrorListener errorListener;


            public SensorOutputWriter(String outputSocketAddress, int delay) {
                this.outputSocketAddress = outputSocketAddress;
                this.delay = delay;
            }

            public SensorOutputWriter(String outputSocketAddress) {
                this(outputSocketAddress, DEFAULT_DELAY);
            }

            public boolean isRunning() {
                return isRunning;
            }

            public void setOnErrorListener(SocketWriterErrorListener errorListener) {
                this.errorListener = errorListener;
            }

            public void setDelay(int delay) {
                this.delay = delay;
            }

            public void setLimit(int limit) {
                this.limit = limit;
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
                                try {
                                    Thread.sleep(this.delay);
                                } catch (InterruptedException e) {
                                    Logger.logInfo(LOG_TAG, "SensorOutputWriter interrupted: " + e.getMessage());
                                }
                                semaphore.acquire();
                                writer.write(sensorReadout.toString(INDENTATION) + "\n");
                                writer.flush();
                                semaphore.release();

                                if (++counter >= limit) {
                                    Logger.logInfo(LOG_TAG, "SensorOutput limit reached! Performing cleanup");
                                    cleanup();
                                }
                            }
                            Logger.logInfo(LOG_TAG, "SensorOutputWriter finished");
                        }
                    }
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "SensorOutputWriter error", e);

                    if (errorListener != null) {
                        errorListener.onError(e);
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

    /**
     * Callback interface for handling exceptions that could occur in SensorOutputWriter
     */
    interface SocketWriterErrorListener {
        void onError(Exception e);
    }


    /**
     * Interface for handling sensor commands
     */
    interface SensorCommandHandler {
        SensorCommandResult handle(SensorManager sensorManager, final Context context, final Intent intent);
    }

    /**
     * Simple POJO to store result of executing a sensor command
     */
    static class SensorCommandResult {
        public String message = "";
        public ResultType type = ResultType.SINGLE;
        public String error;
    }

    enum ResultType {
        SINGLE,
        CONTINUOUS
    }

}

