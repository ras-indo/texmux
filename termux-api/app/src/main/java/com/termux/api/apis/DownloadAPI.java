package com.termux.api.apis;

import android.app.DownloadManager;
import android.app.DownloadManager.Request;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.termux.api.TermuxApiReceiver;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.File;

public class DownloadAPI {

    private static final String LOG_TAG = "DownloadAPI";

    public static void onReceive(TermuxApiReceiver apiReceiver, final Context context, final Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");
        ResultReturner.returnData(apiReceiver, intent, out -> {
            // Read URL from intent extras. The client sends the URL as a bare
            // positional that the native client encodes as {"<url>":true} inside
            // args; populateIntent() mirrors it as an extra with the URL string
            // as key and Boolean.TRUE as value.
            String downloadUrl = null;
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object val = extras.get(key);
                    if (val instanceof Boolean && (Boolean) val) {
                        downloadUrl = key;
                        break;
                    }
                }
            }
            if (downloadUrl == null) {
                out.println(jsonError("No download URI specified"));
                return;
            }
            if (!downloadUrl.startsWith("http://")
                    && !downloadUrl.startsWith("https://")
                    && !downloadUrl.startsWith("ftp://")) {
                out.println(jsonError("Invalid download URL: " + downloadUrl));
                return;
            }

            String title = intent.getStringExtra("title");
            String description = intent.getStringExtra("description");
            String path = intent.getStringExtra("path");

            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                out.println(jsonError("DownloadManager unavailable"));
                return;
            }
            Request req = new Request(Uri.parse(downloadUrl));
            req.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setVisibleInDownloadsUi(true);

            if (title != null)
                req.setTitle(title);
            if (description != null)
                req.setDescription(description);
            if (path != null) {
                try {
                    req.setDestinationUri(Uri.fromFile(new File(path)));
                } catch (Exception e) {
                    out.println(jsonError("Invalid download path: " + path));
                    return;
                }
            }

            long id = manager.enqueue(req);
            if (id <= 0) {
                out.println(jsonError("Failed to enqueue download"));
                return;
            }
            JSONObject resp = new JSONObject();
            try {
                resp.put("success", true);
                resp.put("downloadId", id);
                out.println(resp.toString());
            } catch (Exception e) {
                out.println(jsonError("Failed to format response"));
            }
        });
    }

    private static String jsonError(String message) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("success", false);
            obj.put("error", message);
            return obj.toString();
        } catch (Exception ignored) {
            // Escape backslashes and quotes for the fallback plain-string JSON
            String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
            return "{\"success\":false,\"error\":\"" + escaped + "\"}";
        }
    }
}