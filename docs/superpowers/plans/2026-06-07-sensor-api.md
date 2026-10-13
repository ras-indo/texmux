# Sensor API Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the Sensor API with composite sensor name resolution, configurable delay modes, hardware timestamps, accuracy reporting, and detailed sensor info — while keeping all existing commands (list, cleanup, sensors) fully backward-compatible.

**Architecture:** All changes are isolated to `SensorAPI.java` (470 lines, single-file service). The file contains `SensorReaderService` (inner Service class), `SensorOutputWriter` (Thread polling a shared JSONObject), and handler lambdas dispatched by command name. We modify exactly one file: the SensorAPI.java — no other files need changes because dispatch is already wired through `DirectDispatcher.java` line 200-202.

**Tech Stack:** Android Java (minSdk 24), `android.hardware.SensorManager`, `org.json.JSONObject/JSONArray`, `java.util.Locale`

**File modified only:**
- `/root/termux/termux-app/termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java`

---

### Task 1: Enrich List Handler Output with Full Sensor Metadata

**Goal:** Replace the current list handler (returns array of sensor name strings) with structured JSON objects containing vendor, version, power consumption, resolution, max range, min delay, and type.

**Files:**
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java` lines 194-211

**Current behavior (lines 194-211):**
```java
static SensorCommandHandler listHandler = (sensorManager, context, intent) -> {
    SensorCommandResult result = new SensorCommandResult();
    JSONArray sensorArray = new JSONArray();
    List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_ALL);
    try {
        for (int j = 0; j < sensorList.size(); ++j) {
            Sensor sensor = sensorList.get(j);
            sensorArray.put(sensor.getName());
        }
        JSONObject output = new JSONObject();
        output.put("sensors", sensorArray);
        result.message = output.toString(INDENTATION);
    } catch (JSONException e) {
        Logger.logStackTraceWithMessage(LOG_TAG, "listHandler JSON error", e);
    }
    return result;
};
```

- [ ] **Step 1: Replace listHandler with detailed JSON output**

Replace lines 194-211 with:

```java
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
```

This is a pure additive change to the JSON output. Every existing `Sensor` getter is available since API level 4 (`getMinDelay()` since API 9, which is well below our minSdk 24).

- [ ] **Step 2: Verify the new JSON output format**

The new list output will produce:
```json
{
  "sensors": [
    {
      "name": "BMI260 Accelerometer",
      "vendor": "Bosch",
      "version": 1,
      "type": 1,
      "power_mA": 0.5,
      "resolution": 1.0E-4,
      "max_range": 78.4,
      "min_delay_us": 2500
    },
    {
      "name": "Rotation Vector",
      "vendor": "Google",
      "version": 3,
      "type": 11,
      "power_mA": 0.2,
      "resolution": 1.0E-6,
      "max_range": 1.0,
      "min_delay_us": 5000
    }
  ]
}
```

The `type` field uses the Android `Sensor.TYPE_*` integer constant, allowing consumers to identify sensor category programmatically:
- `Sensor.TYPE_ACCELEROMETER` = 1
- `Sensor.TYPE_GRAVITY` = 9
- `Sensor.TYPE_LINEAR_ACCELERATION` = 10
- `Sensor.TYPE_ROTATION_VECTOR` = 11
etc.

- [ ] **Step 3: Commit**

```bash
git add termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java
git commit -m "feat(sensor): enrich list handler with full sensor metadata (vendor, version, power, resolution, range, minDelay, type)"
```

---

### Task 2: Add Composite Sensor Name Resolution

**Goal:** Ensure standard composite sensor names (`rotation_vector`, `gravity`, `linear_acceleration`) resolve correctly regardless of device-specific naming. The current name-substring matching (`sensorName.contains(requestedSensor)`) fails for names like `rotation_vector` because underscores aren't in real sensor names (e.g., "Rotation Vector Sensor").

**Files:**
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java`

We add a static lookup map and a helper method, and modify `getSensorsToListenTo()` to fall back on type-based matching when name-based matching yields nothing.

- [ ] **Step 1: Add static sensor type name map**

Insert this block after the `semaphore` field declaration (after line 61) inside `SensorReaderService`:

```java
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
```

- [ ] **Step 2: Add helper method for sensor type lookup**

Insert after `clearSensorValues()` (after line 323):

```java
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
```

- [ ] **Step 3: Extend getSensorsToListenTo with type-based fallback**

Replace `getSensorsToListenTo` method (lines 274-312) with an improved version that falls back on type-based resolution:

```java
protected static List<Sensor> getSensorsToListenTo(SensorManager sensorManager, String[] requestedSensors, Intent intent) {
    List<Sensor> availableSensors = new ArrayList<>(sensorManager.getSensorList(Sensor.TYPE_ALL));
    java.util.Collections.sort(availableSensors, (s1, s2) -> s1.getName().compareTo(s2.getName()));
    List<Sensor> sensorsToListenTo = new ArrayList<>();

    boolean listenToAll = intent.getBooleanExtra("all", false);

    if (listenToAll) {
        for (Sensor sensor : availableSensors) {
            sensorManager.registerListener(sensorEventListener, sensor, SensorManager.SENSOR_DELAY_UI);
        }
        sensorsToListenTo = availableSensors;
        Logger.logInfo(LOG_TAG, "Listening to ALL sensors");
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
                sensorManager.registerListener(sensorEventListener, shortestMatchSensor, SensorManager.SENSOR_DELAY_UI);
                sensorsToListenTo.add(shortestMatchSensor);
            } else {
                Logger.logInfo(LOG_TAG, "Sensor not found: '" + trimmedRequest + "'");
            }
        }
    }
    return sensorsToListenTo;
}
```

The matching priority is:
1. **Name-based substring match** — same as current behavior (case-insensitive `contains()`). Catches `"accelerometer"` → `"BMI260 Accelerometer"`.
2. **Type-name-based lookup** — fallback via the `SENSOR_TYPE_NAME_MAP`. Catches `"rotation_vector"`, `"gravity"`, `"linear_acceleration"`, or any standard type name regardless of device naming.

- [ ] **Step 4: Commit**

```bash
git add termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java
git commit -m "feat(sensor): add composite sensor resolution via type-name fallback (rotation_vector, gravity, linear_acceleration)"
```

---

### Task 3: Add Configurable Sensor Delay Mode

**Goal:** Replace the hardcoded `SensorManager.SENSOR_DELAY_UI` with a user-configurable `delay_mode` extra. Accepted values: `"ui"`, `"game"`, `"fastest"`, `"normal"`. Backward compatible — defaults to `"ui"`.

**Files:**
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java`

The change involves:
1. A new helper method `parseDelayMode()` that converts the string extra to a `SensorManager.SENSOR_DELAY_*` integer.
2. Modifying `getSensorsToListenTo()` (and the `listenToAll` path) to accept and use the delay parameter.

- [ ] **Step 1: Add delay mode parsing method**

Insert after the `findSensorByTypeName` method (added in Task 2):

```java
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
```

Delay constant reference (all available since API 3, well below minSdk 24):
| Mode | Constant | Rate | Use case |
|------|----------|------|----------|
| `"normal"` | `SENSOR_DELAY_NORMAL` (3) | ~200,000 μs | Screen orientation changes |
| `"ui"` | `SENSOR_DELAY_UI` (2) | ~60,000 μs | UI updates |
| `"game"` | `SENSOR_DELAY_GAME` (1) | ~20,000 μs | Games |
| `"fastest"` | `SENSOR_DELAY_FASTEST` (0) | ~0 μs (hardware max) | Raw sensor streaming |

- [ ] **Step 2: Modify getSensorsToListenTo to accept and use the delay**

Replace the method signature and all `registerListener` calls within `getSensorsToListenTo` (lines 274-312) to accept and use the delay parameter:

```java
protected static List<Sensor> getSensorsToListenTo(SensorManager sensorManager, String[] requestedSensors, Intent intent) {
    List<Sensor> availableSensors = new ArrayList<>(sensorManager.getSensorList(Sensor.TYPE_ALL));
    java.util.Collections.sort(availableSensors, (s1, s2) -> s1.getName().compareTo(s2.getName()));
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
```

Key change: `final int sensorDelay = parseDelayMode(delayMode);` replaces the hardcoded `SensorManager.SENSOR_DELAY_UI` in both `registerListener` calls.

The `delay_mode` extra is a **separate concern** from the existing `delay` extra (which controls the *output polling interval* in milliseconds in `SensorOutputWriter`). `delay_mode` controls the *hardware sampling rate*, while `delay` controls how often the output writer emits data. They can be set independently.

- [ ] **Step 3: Commit**

```bash
git add termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java
git commit -m "feat(sensor): replace hardcoded SENSOR_DELAY_UI with configurable delay_mode (ui/game/fastest/normal)"
```

---

### Task 4: Add Hardware Timestamp and Accuracy to Sensor Readings

**Goal:** Extend the streaming JSON output to include `SensorEvent.timestamp` (nanoseconds since boot) and `SensorEvent.accuracy` (one of `SENSOR_STATUS_ACCURACY_HIGH/MEDIUM/LOW/UNRELIABLE`).

**Files:**
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java`

Two changes:
1. In `sensorEventListener.onSensorChanged()`: add `timestamp` and `accuracy` to the per-sensor JSON object.
2. In `onAccuracyChanged()`: implement the callback to track accuracy changes (currently the body is empty).

- [ ] **Step 1: Modify onSensorChanged to include timestamp and accuracy**

Replace lines 129-145 (the `onSensorChanged` method):

```java
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
```

Only two lines added:
```java
sensorInfo.put("timestamp", sensorEvent.timestamp);   // line added
sensorInfo.put("accuracy", sensorEvent.accuracy);     // line added
```

- `sensorEvent.timestamp` is a `long` (nanoseconds since system boot — use `SystemClock.elapsedRealtimeNanos()` for comparison). Stored as-is in the JSON output.
- `sensorEvent.accuracy` is an `int` with these values:
  - `SensorManager.SENSOR_STATUS_ACCURACY_HIGH` = 3
  - `SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM` = 2
  - `SensorManager.SENSOR_STATUS_ACCURACY_LOW` = 1
  - `SensorManager.SENSOR_STATUS_ACCURACY_UNRELIABLE` = 0

- [ ] **Step 2: Implement onAccuracyChanged to track accuracy per sensor**

Replace lines 147-151 (the `onAccuracyChanged` stub):

```java
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
```

This ensures accuracy changes between sensor events are captured. If a sensor already has a reading in `sensorReadout`, we update its accuracy in-place. If it doesn't yet have a reading (accuracy changed before the first data event), we create a stub entry.

- [ ] **Step 3: Verify streaming output format**

The new streaming output will look like:
```json
{
  "BMI260 Accelerometer": {
    "values": [0.1, 9.4, 0.5],
    "timestamp": 12345678900000,
    "accuracy": 3
  }
}
```

Where:
- `values`: float array of sensor readings (unchanged)
- `timestamp`: long integer, nanoseconds since system boot (new)
- `accuracy`: integer 0-3, higher is more accurate (new)

- [ ] **Step 4: Commit**

```bash
git add termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java
git commit -m "feat(sensor): add hardware timestamp and accuracy level to sensor reading JSON"
```

---

### Task 5: Verify External Dispatch Wiring

**Goal:** Confirm the existing "Sensor" → `SensorAPI.onReceive` dispatch in `DirectDispatcher.java` is correct and no changes are needed there.

**Files:**
- Read-only: `/root/termux/termux-app/termux-api/app/src/main/java/com/texmux/api/DirectDispatcher.java`

- [ ] **Step 1: Read and verify existing dispatch**

The existing dispatch at line 200-202 of DirectDispatcher.java:

```java
case "Sensor":
    SensorAPI.onReceive(context, intent);
    break;
```

This calls `SensorAPI.onReceive()` which starts `SensorReaderService` with the intent extras. All our new extras (`delay_mode`, composite sensor names, timestamp/accuracy in output) are passed through the same Intent bundle without any modification to the dispatch layer. No change needed.

- [ ] **Step 2: No-op commit** — skip, nothing to change.

---

## Full Build Verification

- [ ] **Compile check:** `./gradlew :termux-api:compileDebugJavaWithJavac` passes
- [ ] **No new imports needed:** All additions use types already imported (`Sensor`, `SensorEvent`, `SensorManager`, `JSONObject`, `JSONArray`, `JSONException`). The map and locale additions use fully qualified names (`java.util.Map`, `java.util.Locale`) within the code to avoid import changes.
- [ ] **Existing "list" command unchanged:** `termux-sensor -l` still works, now with richer JSON
- [ ] **Existing "sensors" command unchanged:** `termux-sensor -s "Accelerometer" -n 1` still works
- [ ] **Existing "cleanup" command unchanged:** `termux-sensor -c` still works
- [ ] **New "delay_mode" extra:** `termux-sensor -s "Accelerometer" --es delay_mode "game" -n 10` uses game rate
- [ ] **Composite sensor names:** `termux-sensor -s "rotation_vector" -n 5` resolves to rotation vector sensor
- [ ] **Timestamp and accuracy:** Sensor JSON output now includes `"timestamp"` and `"accuracy"` fields
- [ ] **Semaphore consistency:** All `semaphore.acquire()` calls have matching `semaphore.release()` — verified by code review

## Rollback Plan

If any issue arises:

1. **Revert the entire change:**
   ```bash
   git checkout termux-api/app/src/main/java/com/texmux/api/apis/SensorAPI.java
   ```

2. **Selective revert by task** using `git revert` with the specific commit hash for each task.

3. **Fallback test:** After revert, run `termux-sensor -l` and `termux-sensor -s "Accelerometer" -n 1` to verify original behavior is restored.

## Summary of Changes

| Task | Lines Changed | Description |
|------|--------------|-------------|
| 1 | 194-211 | `listHandler`: replace name-only array with full metadata JSON objects |
| 2 | 61-area, after 323, 274-312 | Add `SENSOR_TYPE_NAME_MAP`, `findSensorByTypeName()`, extend `getSensorsToListenTo()` |
| 3 | 274-312, new method | Add `parseDelayMode()`, replace hardcoded `SENSOR_DELAY_UI` with configurable delay |
| 4 | 129-151 | Add `timestamp` + `accuracy` to `onSensorChanged`, implement `onAccuracyChanged` |
| 5 | — | Verify `DirectDispatcher.java` dispatch — no changes needed |

All changes are in a single file (`SensorAPI.java`), fully backward-compatible, and follow the existing code style exactly.
