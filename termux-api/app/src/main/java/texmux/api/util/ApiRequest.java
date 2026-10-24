package texmux.api.util;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Structured representation of a Termux API request, parsed from JSON.
 *
 * <p>Replaces the 7-regex {@code --es/--ez/--ei} command-line parsing in
 * {@code SocketListener} with a single-pass JSON parse.  Also serves as
 * the request model for the {@code ApiDaemon}.</p>
 *
 * <p>Wire format (JSON):</p>
 * <pre>{@code
 * {
 *   "correlationId": "req-001",          // optional, auto-generated if absent
 *   "method": "BatteryStatus",           // required
 *   "args": {                            // optional
 *     "key_string": "value",
 *     "key_bool": true,
 *     "key_int": 42,
 *     "key_float": 3.14,
 *     "key_int_array": [1, 2, 3],
 *     "key_long_array": [10000000000]
 *   }
 * }
 * }</pre>
 */
public class ApiRequest {

    public final String correlationId;
    public final String method;
    public final String intentAction;
    public final Map<String, Object> args;

    public ApiRequest(String correlationId, String method, String intentAction, Map<String, Object> args) {
        this.correlationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        this.method = method;
        this.intentAction = intentAction;
        this.args = args != null ? args : new HashMap<>(0);
    }

    // ─── Parsing ───────────────────────────────────────────────────────

    /** Parse a JSON request from a byte array. */
    public static ApiRequest fromJson(byte[] jsonBytes) throws Exception {
        return fromJson(new String(jsonBytes, StandardCharsets.UTF_8));
    }

    /** Parse a JSON request from a string. */
    public static ApiRequest fromJson(String json) throws Exception {
        JSONObject obj = new JSONObject(json);
        String correlationId = obj.optString("correlationId", null);
        String method = obj.getString("method");
        String intentAction = obj.optString("intentAction", null);
        if (intentAction != null && intentAction.isEmpty()) intentAction = null;
        Map<String, Object> args = new HashMap<>();
        if (obj.has("args")) {
            JSONObject argsObj = obj.getJSONObject("args");
            Iterator<String> keys = argsObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                args.put(key, unwrapJsonValue(argsObj.get(key)));
            }
        }
        return new ApiRequest(correlationId, method, intentAction, args);
    }

    /** Parse a JSON request from an input stream (reads until EOF). */
    public static ApiRequest fromStream(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            baos.write(buf, 0, n);
        }
        return fromJson(baos.toByteArray());
    }

    // ─── Accessors (type-safe) ─────────────────────────────────────────

    public @Nullable String getString(String key) {
        Object v = args.get(key);
        return v instanceof String ? (String) v : null;
    }

    public @Nullable String getString(String key, @Nullable String def) {
        String v = getString(key);
        return v != null ? v : def;
    }

    public boolean getBoolean(String key, boolean def) {
        Object v = args.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) {
            String s = ((String) v).toLowerCase();
            if ("true".equals(s) || "t".equals(s)) return true;
            if ("false".equals(s) || "f".equals(s)) return false;
        }
        return def;
    }

    public int getInt(String key, int def) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public float getFloat(String key, float def) {
        Object v = args.get(key);
        if (v instanceof Number) return ((Number) v).floatValue();
        if (v instanceof String) {
            try { return Float.parseFloat((String) v); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public @Nullable int[] getIntArray(String key) {
        Object v = args.get(key);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            int[] r = new int[a.length()];
            for (int i = 0; i < a.length(); i++) r[i] = a.optInt(i, 0);
            return r;
        }
        return null;
    }

    public @Nullable long[] getLongArray(String key) {
        Object v = args.get(key);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            long[] r = new long[a.length()];
            for (int i = 0; i < a.length(); i++) r[i] = a.optLong(i, 0);
            return r;
        }
        return null;
    }

    public @Nullable String[] getStringArray(String key) {
        Object v = args.get(key);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            String[] r = new String[a.length()];
            for (int i = 0; i < a.length(); i++) r[i] = a.optString(i, null);
            return r;
        }
        return null;
    }

    /** Build a cache key from method + sorted args. */
    public String cacheKey() {
        StringBuilder sb = new StringBuilder(method);
        if (intentAction != null) {
            sb.append("|action=").append(intentAction);
        }
        // Sort keys for deterministic cache key
        args.keySet().stream().sorted().forEach(k -> {
            Object v = args.get(k);
            if (v != null) sb.append('|').append(k).append('=').append(v);
        });
        return sb.toString();
    }

    // ─── Bridge: build Intent for legacy handlers ─────────────────────

    /**
     * Populate an {@link Intent} with extras parsed from this request,
     * so existing {@code onReceive(TermuxApiReceiver, Context, Intent)}
     * handlers can be called without modification.
     */
    public void populateIntent(Intent intent) {
        if (intentAction != null) {
            intent.setAction(intentAction);
        }
        intent.putExtra("api_method", method);
        for (Map.Entry<String, Object> e : args.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            if (val == null) continue;
            if (val instanceof String) {
                intent.putExtra(key, (String) val);
            } else if (val instanceof Boolean) {
                intent.putExtra(key, (Boolean) val);
            } else if (val instanceof Integer) {
                intent.putExtra(key, (Integer) val);
            } else if (val instanceof Long) {
                intent.putExtra(key, (Long) val);
            } else if (val instanceof Double || val instanceof Float) {
                intent.putExtra(key, ((Number) val).floatValue());
            } else if (val instanceof JSONArray) {
                JSONArray a = (JSONArray) val;
                if (a.length() > 0) {
                    Object first = a.opt(0);
                    if (first instanceof String) {
                        String[] sa = new String[a.length()];
                        for (int i = 0; i < a.length(); i++) sa[i] = a.optString(i, null);
                        intent.putExtra(key, sa);
                    } else if (first instanceof Integer) {
                        int[] ia = new int[a.length()];
                        for (int i = 0; i < a.length(); i++) ia[i] = a.optInt(i, 0);
                        intent.putExtra(key, ia);
                    } else if (first instanceof Long) {
                        long[] la = new long[a.length()];
                        for (int i = 0; i < a.length(); i++) la[i] = a.optLong(i, 0);
                        intent.putExtra(key, la);
                    }
                }
            }
        }
    }

    // ─── Serialization ────────────────────────────────────────────────

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"correlationId\":\"").append(escapeJson(correlationId))
          .append("\",\"method\":\"").append(escapeJson(method)).append('"');
        if (intentAction != null) {
            sb.append(",\"intentAction\":\"").append(escapeJson(intentAction)).append('"');
        }
        if (!args.isEmpty()) {
            sb.append(",\"args\":{");
            boolean first = true;
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(e.getKey())).append("\":");
                appendJsonValue(sb, e.getValue());
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static Object unwrapJsonValue(Object val) throws Exception {
        if (val == JSONObject.NULL) return null;
        return val; // JSONObject.get already returns String/Boolean/Integer/Long/Double/JSONArray/JSONObject
    }

    private static void appendJsonValue(StringBuilder sb, Object val) {
        if (val == null) { sb.append("null"); return; }
        if (val instanceof String) { sb.append('"').append(escapeJson((String) val)).append('"'); return; }
        if (val instanceof Boolean || val instanceof Integer || val instanceof Long) { sb.append(val); return; }
        if (val instanceof Float || val instanceof Double) { sb.append(val); return; }
        if (val instanceof JSONArray) { sb.append(val); return; }
        if (val instanceof JSONObject) { sb.append(val); return; }
        if (val instanceof int[]) {
            int[] a = (int[]) val;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
            return;
        }
        if (val instanceof long[]) {
            long[] a = (long[]) val;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
            return;
        }
        if (val instanceof String[]) {
            String[] a = (String[]) val;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append('"').append(escapeJson(a[i])).append('"'); }
            sb.append(']');
            return;
        }
        sb.append('"').append(escapeJson(String.valueOf(val))).append('"');
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
