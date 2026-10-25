package texmux.app;

import android.app.Application;
import android.content.Context;

import texmux.app.BuildConfig;
import texmux.shared.errors.Error;
import texmux.shared.logger.Logger;
import texmux.shared.termux.TermuxProot;
import texmux.shared.termux.TermuxConstants;
import texmux.shared.termux.crash.TermuxCrashUtils;
import texmux.shared.termux.file.TermuxFileUtils;
import texmux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import texmux.shared.termux.settings.properties.TermuxAppSharedProperties;
import texmux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import texmux.shared.termux.shell.am.TermuxAmSocketServer;
import texmux.shared.termux.shell.TermuxShellManager;
import texmux.api.util.ResultReturner;
import texmux.shared.termux.theme.TermuxThemeUtils;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        TermuxCrashUtils.setDefaultCrashHandler(this);

        setLogConfig(context);

        Logger.logDebug("Starting Application");

        TermuxProot.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        TermuxShellManager shellManager = TermuxShellManager.init(context);

        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            TermuxAmSocketServer.setupTermuxAmSocketServer(context);

            ResultReturner.context = this.getApplicationContext();

            // Start the socket listener so API tools can connect
            // via the abstract-namespace socket — no am, no daemon,
            // no filesystem socket.  Single path for all API calls.
            try {
                Class<?> sl = Class.forName("texmux.api.SocketListener");
                sl.getMethod("createSocketListener", Application.class).invoke(null, this);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "SocketListener not available", e);
            }
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        TermuxShellEnvironment.init(this);

        // if (isTermuxFilesDirectoryAccessible) {
        //     TermuxShellEnvironment.writeEnvironmentToFile(this);
        // }
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
