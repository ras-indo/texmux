package com.termux.shared.android;

import android.app.PendingIntent;
import android.os.Build;

/**
 * Utilities for working with {@link PendingIntent}.
 */
public class PendingIntentUtils {

    /**
     * Get flags including {@link PendingIntent#FLAG_IMMUTABLE} for API 23+.
     * Callers should OR this with their specific flags (e.g. FLAG_UPDATE_CURRENT).
     *
     * - https://developer.android.com/guide/components/intents-filters#DeclareMutabilityPendingIntent
     * - https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability
     */
    public static int getImmutableFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return PendingIntent.FLAG_IMMUTABLE;
        else
            return 0;
    }

    /**
     * Get flags including {@link PendingIntent#FLAG_MUTABLE} for API 31+.
     * Callers should OR this with their specific flags.
     */
    public static int getMutableFlag() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return PendingIntent.FLAG_MUTABLE;
        else
            return 0;
    }

}
