package texmux.api;

import android.content.Context;

import texmux.shared.logger.Logger;
import texmux.shared.termux.TermuxConstants;
import texmux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;

public final class ApiLogConfig {

    private ApiLogConfig() {}

    public static void setLogConfig(Context context, boolean commitToFile) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_API_APP_NAME.replaceAll("[: ]", ""));
        TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel(true), commitToFile);
    }
}
