# Zero-Latency API Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate `sendOrderedBroadcast`, `goAsync()`/`PendingResult`, and `am` fallback from the termux-api dispatch chain, achieving near-zero-latency communication between the C binary and Java API handlers.

**Architecture:** Keep the existing socket wire protocol (`[2-byte length][UTF-8 payload]`) unchanged. Replace the broadcast dispatch in SocketListener with a direct method call to the API handlers. Modify ResultReturner to skip PendingResult lifecycle when not invoked from a broadcast. This requires changing exactly **3 Java files** in the critical path and **1 C file** for the optional JSON wire format upgrade.

**Tech Stack:** Android Java (termux-api module), C (termux-api-native-package), abstract Unix sockets

---

### Task 1: Modify ResultReturner to Support Non-Broadcast Dispatch

**Files:**
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/termux/api/util/ResultReturner.java`

**Goal:** Make `returnData()` safe to call from a direct (non-broadcast) context. Currently `goAsync()` throws `IllegalStateException` if the BroadcastReceiver was never invoked via `sendOrderedBroadcast`.

- [ ] **Step 1: Add ordered-broadcast guard before `goAsync()`**

**Problem:** Line 235 calls `receiver.goAsync()` unconditionally when `receiver != null`. If we pass a non-registered BroadcastReceiver (from direct dispatch), `goAsync()` crashes because `mPendingResult` is null.

**Fix:** Only call `goAsync()` when the receiver was actually invoked from an ordered broadcast. `isOrderedBroadcast()` safely checks `mPendingResult` without throwing.

Change line 235:
```java
// BEFORE:
final PendingResult asyncResult = receiver != null ? receiver.goAsync() : null;

// AFTER:
final PendingResult asyncResult = (receiver != null && receiver.isOrderedBroadcast()) ? receiver.goAsync() : null;
```

This is the ONLY change needed in ResultReturner. The socket connection logic (lines 244-276) only depends on Intent extras, not on the receiver. The `asyncResult.setResultCode()` and `asyncResult.finish()` calls are already gated by null-checks.

- [ ] **Step 2: Verify the change compiles and logic is sound**

Check that `isOrderedBroadcast()` is a public final method on `BroadcastReceiver` (it is — defined in `android.content.BroadcastReceiver`). No imports needed (already in the file via the class being a BroadcastReceiver subclass).

The behavior matrix:
| Scenario | `receiver` | `isOrderedBroadcast()` | `goAsync()` called? | Works? |
|---|---|---|---|---|
| broadcast path (old) | real BR from system | true | yes | ✅ same as before |
| direct dispatch (new) | dummy BR, no broadcast | false (mPendingResult=null) | no | ✅ safe |
| Activity path | null | N/A | no | ✅ same as before |

- [ ] **Step 3: Commit**

```bash
git add termux-api/app/src/main/java/com/termux/api/util/ResultReturner.java
git commit -m "fix: guard goAsync() with isOrderedBroadcast() check for direct dispatch"
```

---

### Task 2: Create DirectDispatcher Utility Class

**Files:**
- Create: `/root/termux/termux-app/termux-api/app/src/main/java/com/termux/api/DirectDispatcher.java`
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/termux/api/TermuxApiReceiver.java`

**Goal:** Extract the dispatch switch from `TermuxApiReceiver.doWork()` into a reusable static method that can be called both from the broadcast receiver (existing path) and from SocketListener (new direct path).

- [ ] **Step 1: Create DirectDispatcher.java**

Create a new class that hosts the static dispatch logic. This class has no Android framework dependencies — it's just a switch statement with method calls.

```java
package com.texmux.api;

import android.content.Context;
import android.content.Intent;

import com.texmux.api.apis.AudioAPI;
import com.texmux.api.apis.BatteryStatusAPI;
import com.texmux.api.apis.BrightnessAPI;
import com.texmux.api.apis.CallLogAPI;
import com.texmux.api.apis.CameraInfoAPI;
import com.texmux.api.apis.CameraPhotoAPI;
import com.texmux.api.apis.ClipboardAPI;
import com.texmux.api.apis.ContactListAPI;
import com.texmux.api.apis.DialogAPI;
import com.texmux.api.apis.DownloadAPI;
import com.texmux.api.apis.FingerprintAPI;
import com.texmux.api.apis.InfraredAPI;
import com.texmux.api.apis.JobSchedulerAPI;
import com.texmux.api.apis.KeystoreAPI;
import com.texmux.api.apis.LocationAPI;
import com.texmux.api.apis.MediaPlayerAPI;
import com.texmux.api.apis.MediaScannerAPI;
import com.texmux.api.apis.MicRecorderAPI;
import com.texmux.api.apis.NfcAPI;
import com.texmux.api.apis.NotificationAPI;
import com.texmux.api.apis.NotificationListAPI;
import com.texmux.api.apis.SAFAPI;
import com.texmux.api.apis.SensorAPI;
import com.texmux.api.apis.ShareAPI;
import com.texmux.api.apis.SmsInboxAPI;
import com.texmux.api.apis.SmsSendAPI;
import com.texmux.api.apis.SpeechToTextAPI;
import com.texmux.api.apis.StorageGetAPI;
import com.texmux.api.apis.TelephonyAPI;
import com.texmux.api.apis.TextToSpeechAPI;
import com.texmux.api.apis.ToastAPI;
import com.texmux.api.apis.TorchAPI;
import com.texmux.api.apis.UsbAPI;
import com.texmux.api.apis.VibrateAPI;
import com.texmux.api.apis.VolumeAPI;
import com.texmux.api.apis.WallpaperAPI;
import com.texmux.api.apis.WifiAPI;
import com.texmux.api.activities.TermuxApiPermissionActivity;
import android.Manifest;
import android.content.ComponentName;
import android.provider.Settings;
import android.widget.Toast;

import com.termux.shared.logger.Logger;

/**
 * Dispatches API method calls to the appropriate handler.
 * Used by both TermuxApiReceiver (broadcast path) and SocketListener (direct path).
 */
public class DirectDispatcher {

    private static final String LOG_TAG = "DirectDispatcher";

    /**
     * Dispatch an API call to the appropriate handler.
     *
     * @param handlerContext the object to pass as the first parameter to handlers
     *                       (typically a TermuxApiReceiver for broadcast path,
     *                        or the application Context for direct path)
     * @param context Android context for system services
     * @param intent Intent containing api_method and all extras
     */
    public static void dispatch(Object handlerContext, Context context, Intent intent) {
        String apiMethod = intent.getStringExtra("api_method");
        if (apiMethod == null) {
            Logger.logError(LOG_TAG, "Missing 'api_method' extra");
            return;
        }

        switch (apiMethod) {
            case "AudioInfo":
                AudioAPI.onReceive(handlerContext, context, intent);
                break;
            case "BatteryStatus":
                BatteryStatusAPI.onReceive(handlerContext, context, intent);
                break;
            case "Brightness":
                if (!Settings.System.canWrite(context)) {
                    TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.WRITE_SETTINGS);
                    Toast.makeText(context, "Please enable permission for Termux:API", Toast.LENGTH_LONG).show();
                    Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    context.startActivity(settingsIntent);
                    return;
                }
                BrightnessAPI.onReceive(handlerContext, context, intent);
                break;
            case "CameraInfo":
                CameraInfoAPI.onReceive(handlerContext, context, intent);
                break;
            case "CameraPhoto":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CAMERA)) {
                    CameraPhotoAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "CallLog":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CALL_LOG)) {
                    CallLogAPI.onReceive(context, intent);
                }
                break;
            case "Clipboard":
                ClipboardAPI.onReceive(handlerContext, context, intent);
                break;
            case "ContactList":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_CONTACTS)) {
                    ContactListAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "Dialog":
                DialogAPI.onReceive(context, intent);
                break;
            case "Download":
                DownloadAPI.onReceive(handlerContext, context, intent);
                break;
            case "Fingerprint":
                FingerprintAPI.onReceive(context, intent);
                break;
            case "InfraredFrequencies":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveCarrierFrequency(handlerContext, context, intent);
                }
                break;
            case "InfraredTransmit":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.TRANSMIT_IR)) {
                    InfraredAPI.onReceiveTransmit(handlerContext, context, intent);
                }
                break;
            case "JobScheduler":
                JobSchedulerAPI.onReceive(handlerContext, context, intent);
                break;
            case "Keystore":
                KeystoreAPI.onReceive(context, intent);
                break;
            case "Location":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    LocationAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "MediaPlayer":
                MediaPlayerAPI.onReceive(context, intent);
                break;
            case "MediaScanner":
                MediaScannerAPI.onReceive(handlerContext, context, intent);
                break;
            case "MicRecorder":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    MicRecorderAPI.onReceive(context, intent);
                }
                break;
            case "Nfc":
                NfcAPI.onReceive(context, intent);
                break;
            case "Notification":
                NotificationAPI.onReceiveShowNotification(handlerContext, context, intent);
                break;
            case "NotificationChannel":
                NotificationAPI.onReceiveChannel(handlerContext, context, intent);
                break;
            case "NotificationList":
                ComponentName cn = new ComponentName(context, NotificationListAPI.NotificationService.class);
                String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
                boolean NotificationServiceEnabled = flat != null && flat.contains(cn.flattenToString());
                if (!NotificationServiceEnabled) {
                    Toast.makeText(context, "Please give Termux:API Notification Access", Toast.LENGTH_LONG).show();
                    context.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {
                    NotificationListAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "NotificationRemove":
                NotificationAPI.onReceiveRemoveNotification(handlerContext, context, intent);
                break;
            case "NotificationReply":
                NotificationAPI.onReceiveReplyToNotification(handlerContext, context, intent);
                break;
            case "SAF":
                SAFAPI.onReceive(handlerContext, context, intent);
                break;
            case "Sensor":
                SensorAPI.onReceive(context, intent);
                break;
            case "Share":
                ShareAPI.onReceive(handlerContext, context, intent);
                break;
            case "SmsInbox":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)) {
                    SmsInboxAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "SmsSend":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE, Manifest.permission.SEND_SMS)) {
                    SmsSendAPI.onReceive(handlerContext, context, intent);
                }
                break;
            case "SpeechToText":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.RECORD_AUDIO)) {
                    SpeechToTextAPI.onReceive(context, intent);
                }
                break;
            case "StorageGet":
                StorageGetAPI.onReceive(handlerContext, context, intent);
                break;
            case "TelephonyCall":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.CALL_PHONE)) {
                    TelephonyAPI.onReceiveTelephonyCall(handlerContext, context, intent);
                }
                break;
            case "TelephonyCellInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    TelephonyAPI.onReceiveTelephonyCellInfo(handlerContext, context, intent);
                }
                break;
            case "TelephonyDeviceInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.READ_PHONE_STATE)) {
                    TelephonyAPI.onReceiveTelephonyDeviceInfo(handlerContext, context, intent);
                }
                break;
            case "TextToSpeech":
                TextToSpeechAPI.onReceive(context, intent);
                break;
            case "Toast":
                ToastAPI.onReceive(context, intent);
                break;
            case "Torch":
                TorchAPI.onReceive(handlerContext, context, intent);
                break;
            case "Usb":
                UsbAPI.onReceive(context, intent);
                break;
            case "Vibrate":
                VibrateAPI.onReceive(handlerContext, context, intent);
                break;
            case "Volume":
                VolumeAPI.onReceive(handlerContext, context, intent);
                break;
            case "Wallpaper":
                WallpaperAPI.onReceive(context, intent);
                break;
            case "WifiConnectionInfo":
                WifiAPI.onReceiveWifiConnectionInfo(handlerContext, context, intent);
                break;
            case "WifiScanInfo":
                if (TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    WifiAPI.onReceiveWifiScanInfo(handlerContext, context, intent);
                }
                break;
            case "WifiEnable":
                WifiAPI.onReceiveWifiEnable(handlerContext, context, intent);
                break;
            default:
                Logger.logError(LOG_TAG, "Unrecognized 'api_method' extra: '" + apiMethod + "'");
        }
    }
}
```

**Key design decision:** The `handlerContext` parameter replaces `this` (the TermuxApiReceiver instance) in the existing code. For the broadcast path, TermuxApiReceiver passes itself (`this`). For the direct path, SocketListener passes `ResultReturner.context` (the application Context). Since Context is NOT a BroadcastReceiver, ResultReturner skips the `goAsync()`/`PendingResult` path (fixed in Task 1).

Handlers that take `(TermuxApiReceiver, Context, Intent)` will receive `(Context appContext, Context androidContext, Intent intent)` — this compiles because `TermuxApiReceiver` is an `Object`, and `Context` is also an `Object`, and in Java, `Object` is the erasure of the generic first parameter... wait, no. The handler signatures are:

```java
public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, Intent intent)
```

The first parameter is specifically `TermuxApiReceiver`, not `Object` or `Context`. So we can't pass a plain `Context` there — it won't compile.

**Fix for this issue:** We need the `handlerContext` to actually be a `TermuxApiReceiver` instance. But we don't want it to be a registered BroadcastReceiver.

**Solution:** Create a lightweight non-registered `TermuxApiReceiver` instance in SocketListener. Since `isOrderedBroadcast()` on it returns false (no broadcast was sent), and the Task 1 fix skips `goAsync()` in that case, this works.

```java
// In SocketListener's new dispatch path:
private static final TermuxApiReceiver directReceiver = new TermuxApiReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Never invoked by the system — we call DirectDispatcher directly
    }
};
```

This creates a TermuxApiReceiver that:
- IS-A `TermuxApiReceiver` → passes the type check for handler signatures ✅
- IS-A `BroadcastReceiver` → but `isOrderedBroadcast()` returns false ✅ (no broadcast → no mPendingResult)
- ResultReturner gets it → `isOrderedBroadcast()` is false → skips `goAsync()` ✅ (Task 1 guard)
- Handler calls `ResultReturner.returnData(directReceiver, intent, writer)` → works ✅

- [ ] **Step 2: Simplify TermuxApiReceiver.doWork() to delegate to DirectDispatcher**

```java
// In TermuxApiReceiver.java, replace the entire doWork() method:
private void doWork(Context context, Intent intent) {
    DirectDispatcher.dispatch(this, context, intent);
}
```

This eliminates the ~200-line switch statement from TermuxApiReceiver and moves it to DirectDispatcher. The import list in TermuxApiReceiver can be significantly reduced.

- [ ] **Step 3: Commit**

```bash
git add termux-api/app/src/main/java/com/termux/api/DirectDispatcher.java \
       termux-api/app/src/main/java/com/termux/api/TermuxApiReceiver.java
git commit -m "refactor: extract API dispatch into reusable DirectDispatcher class"
```

---

### Task 3: Refactor SocketListener for Direct Dispatch

**Files:**
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/termux/api/SocketListener.java`

**Goal:** Replace `sendOrderedBroadcast()` with a direct call to `DirectDispatcher.dispatch()`. Keep the same cmdline parsing and Intent construction — only the final dispatch mechanism changes.

- [ ] **Step 1: Replace broadcast with direct dispatch**

The change is localized to lines 56-241 of SocketListener.java. Instead of constructing an Intent targeting TermuxApiReceiver.class and calling `sendOrderedBroadcast`, we:
1. Keep all the parsing code and Intent construction (lines 58-237)
2. Keep the ack write (lines 240-241)
3. Call `DirectDispatcher.dispatch()` instead of `sendOrderedBroadcast()`

**The Intent target class changes** — currently it explicitly targets `TermuxApiReceiver.class`:
```java
Intent intent = new Intent(app.getApplicationContext(), TermuxApiReceiver.class);
```

For direct dispatch, the Intent just carries data (extras). The target class doesn't matter because we're not sending a broadcast. We can use:
```java
Intent intent = new Intent();
```

Or keep the target class for clarity. Either works since we bypass the broadcast machinery.

**Replace line 238** (`sendOrderedBroadcast`):

```java
// BEFORE (line 238):
app.getApplicationContext().sendOrderedBroadcast(intent, null);

// AFTER:
DirectDispatcher.dispatch(directReceiver, app.getApplicationContext(), intent);
```

Add the `directReceiver` field to the class:

```java
// Add as a static field (alongside the `listener` field at line 33):
private static final TermuxApiReceiver directReceiver = new TermuxApiReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Unused — dispatch happens via DirectDispatcher
    }
};
```

Add imports at the top:
```java
import android.content.Context;
```

- [ ] **Step 2: Remove Intent target class construction**

Change the Intent creation line:
```java
// BEFORE:
Intent intent = new Intent(app.getApplicationContext(), TermuxApiReceiver.class);

// AFTER:
Intent intent = new Intent();
```

(The Action pattern `-a` flag parsing at line 187-191 already sets the action via `intent.setAction()`, so removing the target class is safe.)

- [ ] **Step 3: Verify and Commit**

The final SocketListener.java should have:
- No `import android.content.Intent` removed (still needed for Intent)
- `import com.texmux.api.TermuxApiReceiver` added (for the directReceiver field type)
- `import com.texmux.api.DirectDispatcher` added
- Line 238 changed from `sendOrderedBroadcast` to `DirectDispatcher.dispatch()`
- `directReceiver` static field added

```bash
git add termux-api/app/src/main/java/com/termux/api/SocketListener.java
git commit -m "perf: replace sendOrderedBroadcast with direct dispatch for zero-latency API calls"
```

---

### Task 4: Optional — Change Wire Format from --es Flags to JSON

**Files:**
- Modify: `/root/termux/termux-app/termux-api-native-package/termux-api.c`
- Modify: `/root/termux/termux-app/termux-api/app/src/main/java/com/termux/api/SocketListener.java`

**Goal:** Replace the adb-style `--es`/`--ez`/`--ei`/`--ef`/`--eia`/`--ela` flag encoding with clean JSON on the wire. This simplifies the C code, the Java parsing (replace 8 regexes with 1 JSON parser), and makes the protocol extensible.

**Important:** This is OPTIONAL. The zero-latency benefit comes from Task 3 (direct dispatch). JSON on the wire just simplifies the code and makes it more maintainable. The old `--es`/`--ez` wire format still works with the old parsing code, so this can be deferred or skipped.

**Architecture note:** The C binary (`termux-api.c` `contact_plugin()`) currently builds the command string by concatenating flag-style args. The string includes `socket_input`, `socket_output`, `api_method`, and all shell-script-provided extras (`--es title "Hello"`). For JSON, the C binary would build a JSON object where the extras are already typed (since the flag names encode the type).

**JSON format proposal:**
```json
{
  "api_method": "BatteryStatus",
  "socket_input": "...",
  "socket_output": "...",
  "args": {
    "title": {"type": "string", "value": "Hello"},
    "sound": {"type": "boolean", "value": true},
    "led_on": {"type": "int", "value": 500}
  }
}
```

Or simpler — since the types are known from the flag names, just encode with typed values:
```json
{
  "api_method": "BatteryStatus",
  "socket_input": "...",
  "socket_output": "...",
  "extras": {
    "title": "Hello",
    "sound": true,
    "led_on": 500,
    "vibrate": [100, 200, 300]
  }
}
```

To keep it simple: numbers = int, true/false = boolean, strings with quotes = string, arrays = int[]/long[].

Actually, for minimal change, let's just wrap the existing format into JSON. The C code currently builds a flat string. We change it to build a JSON string. The Java side parses JSON instead of using 8 regex patterns.

**C code change (contact_plugin()):**
Build a JSON object string instead of the adb-style flag string. JavaScript-like string concatenation.

```c
// Example JSON construction for "BatteryStatus" with no extras:
// {"api_method":"BatteryStatus","socket_input":"<uuid>","socket_output":"<uuid>"}
```

The full rework of the C string building is complex (escaping, JSON string building). Consider using a simple approach: build a JSON string with `snprintf()`.

- [ ] **Step 1: Rebuild contact_plugin() to output JSON**

Replace the adb-style flag string assembly in `contact_plugin()` (lines 77-176) with JSON string assembly.

The JSON payload:
```json
{
  "socket_output": "<input_addr_str>",
  "socket_input": "<output_addr_str>",
  "api_method": "<argv[1]>",
  "extras": {
    "<key>": <typed-value>,
    ...
  }
}
```

C code approach — use a dynamic buffer with `snprintf()`:

```c
// Calculate required size
size_t bufsize = 256; // base overhead
bufsize += strlen(input_addr_str) + strlen(output_addr_str) + strlen(argv[1]);
for (int i = 2; i < argc; i++) {
    bufsize += strlen(argv[i]) + 4; // key/value with JSON overhead
}
// Also account for JSON escaping of " and \ characters

char* buffer = malloc(bufsize);

// Build JSON object
int offset = snprintf(buffer, bufsize,
    "{\"api_method\":\"%s\",\"socket_input\":\"%s\",\"socket_output\":\"%s\"",
    argv[1], output_addr_str, input_addr_str);

// Add extras if any
bool has_extras = false;
for (int i = 2; i < argc; i++) {
    if (!has_extras) {
        offset += snprintf(buffer + offset, bufsize - offset, ",\"extras\":{");
        has_extras = true;
    }
    // Process flag-value pairs
    if (strcmp(argv[i], "--es") == 0 || strcmp(argv[i], "-e") == 0) {
        // --es key value → "key": "value" (with JSON escaping)
        offset += snprintf(buffer + offset, bufsize - offset,
            "\"%s\":\"%s\"", argv[i+1], json_escape(argv[i+2]));
        i += 2;
    } else if (strcmp(argv[i], "--ez") == 0) {
        offset += snprintf(buffer + offset, bufsize - offset,
            "\"%s\":%s", argv[i+1], argv[i+2]); // true/false without quotes
        i += 2;
    } else if (strcmp(argv[i], "--ei") == 0) {
        offset += snprintf(buffer + offset, bufsize - offset,
            "\"%s\":%s", argv[i+1], argv[i+2]);
        i += 2;
    }
    // ... etc for --ef, --ez, --eia, --ela
    if (i + 2 < argc) offset += snprintf(buffer + offset, bufsize - offset, ",");
}

if (has_extras) {
    offset += snprintf(buffer + offset, bufsize - offset, "}");
}

offset += snprintf(buffer + offset, bufsize - offset, "}");
```

Note: JSON escaping for strings requires escaping `"` → `\"`, `\` → `\\`, and control characters. Add a `json_escape()` helper function.

- [ ] **Step 2: Replace Java regex parsing with JSON parsing**

In `SocketListener.java`, replace the 8 regex patterns + 7 HashMap populations (lines 58-237) with a single JSON parse:

```java
// AFTER reading cmdline from socket:
import org.json.JSONObject;

JSONObject json = new JSONObject(cmdline);
String apiMethod = json.getString("api_method");

Intent intent = new Intent();
intent.putExtra("api_method", apiMethod);
intent.putExtra("socket_input", json.optString("socket_input", ""));
intent.putExtra("socket_output", json.optString("socket_output", ""));

JSONObject extras = json.optJSONObject("extras");
if (extras != null) {
    for (String key : extras.keySet()) {
        Object val = extras.get(key);
        if (val instanceof String) {
            intent.putExtra(key, (String) val);
        } else if (val instanceof Integer) {
            intent.putExtra(key, (Integer) val);
        } else if (val instanceof Boolean) {
            intent.putExtra(key, (Boolean) val);
        } else if (val instanceof Double) {
            intent.putExtra(key, (Float) ((Double) val).floatValue());
        } else if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            if (arr.length() > 0) {
                Object first = arr.get(0);
                if (first instanceof Integer) {
                    int[] ints = new int[arr.length()];
                    for (int i = 0; i < arr.length(); i++) ints[i] = arr.getInt(i);
                    intent.putExtra(key, ints);
                } else if (first instanceof Long) {
                    long[] longs = new long[arr.length()];
                    for (int i = 0; i < arr.length(); i++) longs[i] = arr.getLong(i);
                    intent.putExtra(key, longs);
                }
            }
        }
    }
}

// Then dispatch directly
DirectDispatcher.dispatch(directReceiver, app.getApplicationContext(), intent);
con.getOutputStream().write(0);
con.getOutputStream().flush();
```

This eliminates ~170 lines of regex code and replaces it with ~40 lines of clean JSON parsing.

- [ ] **Step 3: Remove unused regex patterns and imports**

After the JSON change, remove these fields:
```java
private static final Pattern EXTRA_STRING = ...;
private static final Pattern EXTRA_BOOLEAN = ...;
private static final Pattern EXTRA_INT = ...;
private static final Pattern EXTRA_FLOAT = ...;
private static final Pattern EXTRA_INT_LIST = ...;
private static final Pattern EXTRA_LONG_LIST = ...;
private static final Pattern EXTRA_UNSUPPORTED = ...;
private static final Pattern ACTION = ...;
```

Remove unused imports: `java.util.regex.Matcher`, `java.util.regex.Pattern`, `java.util.HashMap`, `java.util.Map`.

- [ ] **Step 4: Commit**

```bash
git add termux-api-native-package/termux-api.c \
       termux-api/app/src/main/java/com/termux/api/SocketListener.java
git commit -m "refactor: replace adb-style flag encoding with JSON wire protocol"
```

---

### Task 5: Wire Up App Startup

**Files:**
- Modify: `/root/termux/termux-app/app/src/main/java/com/termux/app/TermuxApplication.java`

**Goal:** Ensure the SocketListener daemon starts in Texmux app process (not just in termux-api standalone APK).

- [ ] **Step 1: Verify SocketListener is started at app boot**

Read `TermuxAPIApplication.java` from the termux-api module:

```java
// termux-api/TermuxAPIApplication.java
public void onCreate() {
    super.onCreate();
    ResultReturner.setContext(this);
    setLogConfig(context, true);
    SocketListener.createSocketListener(this);
}
```

In TermuxApplication.java of the main app, ensure `SocketListener.createSocketListener()` is called:

```java
// In TermuxApplication.onCreate():
SocketListener.createSocketListener(this);
```

And `ResultReturner.setContext(this)` is called.

If already present (from earlier work), skip this step.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxApplication.java
git commit -m "fix: ensure SocketListener starts at app boot"
```

---

### Task 6: Script Cleanup — Remove Broadcast-Only Scripts

**Files:**
- Modify: `/root/termux/termux-app/app/src/main/assets/termux-api/bin/termux-api-start`
- Modify: `/root/termux/termux-app/app/src/main/assets/termux-api/bin/termux-api-stop`

**Goal:** These scripts exist solely to start/stop a KeepAliveService that was needed when termux-api was a separate APK. Since the SocketListener now runs in-process in the main Texmux app, these are no-ops.

- [ ] **Step 1: Replace termux-api-start with no-op**

```bash
#!/system/bin/sh
# termux-api-start — no longer needed. SocketListener runs in-process.
exit 0
```

- [ ] **Step 2: Replace termux-api-stop with no-op**

```bash
#!/system/bin/sh
# termux-api-stop — no longer needed. SocketListener runs in-process.
exit 0
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/termux-api/bin/termux-api-start \
       app/src/main/assets/termux-api/bin/termux-api-stop
git commit -m "chore: replace api-start/stop scripts with no-ops (server is in-process)"
```

---

### Task 7: Remove BroadcastReceiver Registration from Manifest (ONLY if safe)

**Files:**
- Modify: `/root/termux/termux-api/app/src/main/AndroidManifest.xml`

**Goal:** Optionally remove the `TermuxApiReceiver` broadcast receiver registration from the manifest, since it's no longer needed for the dispatch path.

**CONSIDERATION:** The broadcast receiver is still needed if:
- Other apps or processes send explicit broadcasts to it
- The `am broadcast` fallback path is still enabled (Android 14+)
- Some external workflow depends on it

**If the `am broadcast` fallback is still needed for Android 14+**, keep the receiver registered. The `exec_am_broadcast()` path in the C binary still sends `am broadcast` commands, which need the receiver.

**If the `am broadcast` fallback is completely removed**, remove the receiver from manifest.

**DECISION:** Keep the receiver in the manifest for now. The `am broadcast` fallback in `termux-api.c` is still compiled in for Android 14+ safety. The receiver being registered but unused for direct dispatch has zero overhead.

- [ ] **Step 1: Verify current registration in manifest**

```xml
<receiver android:name=".TermuxApiReceiver"
    android:exported="false" />
```

Since `exported="false"` and no intent filters, the receiver has zero overhead when not explicitly targeted. No change needed.

- [ ] **Step 2: Skip this task** — No manifest changes required.

---

### Task 8: Fix Package Name Typo (texmux → termux)

**Files:**
- All 37 handler files under `/root/termux/termux-app/termux-api/app/src/main/java/com/termux/api/apis/*.java`
- Utility files under `/root/termux/termux-app/termux-api/app/src/main/java/com/termux/api/util/*.java`
- Main API files (`SocketListener.java`, `TermuxApiReceiver.java`, `DirectDispatcher.java`, etc.)

**Goal:** Fix the systematic package name typo `com.texmux.api` → `com.termux.api` across all Java source files in the termux-api module.

**Note:** This is a cosmetic/bugfix task that doesn't affect functionality (the PACKAGE_NAME constant in TermuxConstants is what matters for runtime lookups). Can be done as a batch find-and-replace.

- [ ] **Step 1: Batch replace package declarations**

Replace `package com.texmux.api` with `package com.termux.api` in all `.java` files:

```bash
find termux-api/app/src/main/java -name "*.java" -exec sed -i 's/package com\.texmux\.api/package com.termux.api/g' {} +
```

- [ ] **Step 2: Batch replace imports**

Replace `import com.texmux.api` with `import com.termux.api` in all `.java` files:

```bash
find termux-api/app/src/main/java -name "*.java" -exec sed -i 's/import com\.texmux\.api/import com.termux.api/g' {} +
```

- [ ] **Step 3: Commit**

```bash
git add termux-api/app/src/main/java
git commit -m "fix: correct package name typo com.texmux.api → com.termux.api"
```

---

## Verification Checklist

After all tasks are implemented:

- [ ] **Build check:** `./gradlew :termux-api:compileDebugJavaWithJavac` passes
- [ ] **Full APK build:** `./gradlew assembleDebug` succeeds
- [ ] **SocketListener starts:** Verify `SocketListener.createSocketListener()` is called in `TermuxApplication.onCreate()`
- [ ] **BatteryStatus:** From shell inside Texmux, run `termux-battery-status` and verify JSON output
- [ ] **Toast:** Run `termux-toast "hello"` and verify toast appears
- [ ] **Clipboard:** Run `termux-clipboard-set "test" && termux-clipboard-get` and verify roundtrip
- [ ] **Notification:** Run `termux-notification -t "Test" -c "Body"` and verify notification appears
- [ ] **Sensor:** Run `termux-sensor -s "Accelerometer" -n 1` and verify sensor data
- [ ] **SCM_RIGHTS preserved:** Run `termux-usb -l` and verify USB device list
- [ ] **No regression:** Verify `am broadcast` fallback still works if invoked externally
- [ ] **Lint clean:** No `isOrderedBroadcast()` false-positives or warnings

## Rollback Plan

If the direct dispatch causes issues:
1. Revert Task 3: `git revert <commit-hash>` for SocketListener change
2. The broadcast path is still in the code via the existing `TermuxApiReceiver`
3. The `exec_am_broadcast()` fallback in the C binary is unchanged
4. The manifest still has the receiver registered

## Parallel Execution Opportunities

The following tasks are independent and can run in parallel:
- Task 1 (ResultReturner) + Task 2 (DirectDispatcher) + Task 6 (scripts) + Task 8 (package name fix)

Task 3 (SocketListener) depends on Task 1 + Task 2 being complete.
Task 4 (JSON wire format) can be done in parallel but modifies SocketListener.java — conflicts with Task 3.
