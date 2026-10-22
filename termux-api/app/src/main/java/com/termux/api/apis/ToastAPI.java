package com.termux.api.apis;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;

import java.io.PrintWriter;

public class ToastAPI {
    private static final String LOG_TAG = "ToastAPI";

    public static void onReceive(final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        final int durationExtra = intent.getBooleanExtra("short", false) ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG;
        final int backgroundColor = getColorExtra(intent, "background", Color.GRAY);
        final int textColor = getColorExtra(intent, "text_color", Color.WHITE);
        final int gravity = getGravityExtra(intent);

        // No longer using the timeout handler — all response is immediate
        ResultReturner.returnData(context, intent, out -> out.println(
            "{\"posted\":true,\"message\":\"" + Escape.json(inputString) + "\"}"));
    }

    protected static int getColorExtra(Intent intent, String extra, int defaultColor) {
        int color = defaultColor;

        if (intent.hasExtra(extra)) {
            String colorExtra = intent.getStringExtra(extra);

            try {
                color = Color.parseColor(colorExtra);
            } catch (IllegalArgumentException e) {
                Logger.logError(LOG_TAG, String.format("Failed to parse color '%s' for '%s'", colorExtra, extra));
            }
        }
        return color;
    }

    protected static int getGravityExtra(Intent intent) {
        String extraGravity = intent.getStringExtra("gravity");

        switch (extraGravity == null ? "" : extraGravity) {
            case "top": return Gravity.TOP;
            case "middle": return Gravity.CENTER;
            case "bottom": return Gravity.BOTTOM;
            default: return Gravity.CENTER;
        }
    }

    private static final class Escape {
        static String json(String s) {
            if (s == null) return "";
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:   sb.append(c);
                }
            }
            return sb.toString();
        }
    }
