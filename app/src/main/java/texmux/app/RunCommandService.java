package texmux.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import texmux.app.R;
import texmux.shared.data.DataUtils;
import texmux.shared.data.IntentUtils;
import texmux.shared.termux.plugins.TermuxPluginUtils;
import texmux.shared.termux.file.TermuxFileUtils;
import texmux.shared.file.filesystem.FileType;
import texmux.shared.errors.Errno;
import texmux.shared.errors.Error;
import texmux.shared.termux.TermuxProot;
import texmux.shared.termux.TermuxConstants;
import texmux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;
import texmux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import texmux.shared.file.FileUtils;
import texmux.shared.logger.Logger;
import texmux.shared.notification.NotificationUtils;
import texmux.shared.shell.command.ExecutionCommand;
import texmux.shared.shell.command.ExecutionCommand.Runner;
import androidx.core.app.NotificationCompat;

/**
 * A service that receives {@link RUN_COMMAND_SERVICE#ACTION_RUN_COMMAND} intent from third party apps and
 * plugins that contains info on command execution and forwards the extras to {@link TermuxService}
 * for the actual execution.
 *
 * Check https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent for more info.
 */
public class RunCommandService extends Service {

    private static final String LOG_TAG = "RunCommandService";

    class LocalBinder extends Binder {
        public final RunCommandService service = RunCommandService.this;
    }

    private final IBinder mBinder = new RunCommandService.LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");
        runStartForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        if (intent == null) return Service.START_NOT_STICKY;

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.pluginAPIHelp = this.getString(R.string.error_run_command_service_api_help, RUN_COMMAND_SERVICE.RUN_COMMAND_API_HELP_URL);

        Error error;
        String errmsg;

        // If invalid action passed, then just return
        if (!RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND.equals(intent.getAction())) {
            errmsg = this.getString(R.string.error_run_command_service_invalid_intent_action, intent.getAction());
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return stopService();
        }

        String executableExtra = executionCommand.executable = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, null);
        executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, null);

        /*
        * If intent was sent with `am` command, then normal comma characters may have been replaced
        * with alternate characters if a normal comma existed in an argument itself to prevent it
        * splitting into multiple arguments by `am` command.
        * If `tudo` or `sudo` are used, then simply using their `-r` and `--comma-alternative` command
        * options can be used without passing the below extras, but native supports is helpful if
        * they are not being used.
        * https://github.com/agnostic-apollo/tudo#passing-arguments-using-run_command-intent
        * https://android.googlesource.com/platform/frameworks/base/+/21bdaf1/cmds/am/src/com/android/commands/am/Am.java#572
        */
        boolean replaceCommaAlternativeCharsInArguments = intent.getBooleanExtra(RUN_COMMAND_SERVICE.EXTRA_REPLACE_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS, false);
        if (replaceCommaAlternativeCharsInArguments) {
            String commaAlternativeCharsInArguments = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS, null);
            if (commaAlternativeCharsInArguments == null)
                commaAlternativeCharsInArguments = TermuxConstants.COMMA_ALTERNATIVE;
            // Replace any commaAlternativeCharsInArguments characters with normal commas
            DataUtils.replaceSubStringsInStringArrayItems(executionCommand.arguments, commaAlternativeCharsInArguments, TermuxConstants.COMMA_NORMAL);
        }

        executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_STDIN, null);
        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_WORKDIR, null);

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RUNNER,
            (intent.getBooleanExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, false) ? Runner.APP_SHELL.getName() : Runner.TERMINAL_SESSION.getName()));
        if (Runner.runnerOf(executionCommand.runner) == null) {
            errmsg = this.getString(R.string.error_run_command_service_invalid_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return stopService();
        }

        executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        executionCommand.sessionAction = intent.getStringExtra(RUN_COMMAND_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_SHELL_NAME, null);
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_SHELL_CREATE_MODE, null);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_LABEL, "RUN_COMMAND Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.isPluginExecutionCommand = true;
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        // If "allow-external-apps" property to not set to "true", then just return
        // We enable force notifications if "allow-external-apps" policy is violated so that the
        // user knows someone tried to run a command in termux context, since it may be malicious
        // app or imported (tasker) plugin project and not the user himself. If a pending intent is
        // also sent, then its creator is also logged and shown.
        errmsg = TermuxPluginUtils.checkIfAllowExternalAppsPolicyIsViolated(this, LOG_TAG);
        if (errmsg != null) {
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, true);
            return stopService();
        }



        // If executable is null or empty, then exit here instead of getting canonical path which would expand to "/"
        if (executionCommand.executable == null || executionCommand.executable.isEmpty()) {
            errmsg  = this.getString(R.string.error_run_command_service_mandatory_extra_missing, RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return stopService();
        }

        if (TermuxProot.isProotVariant()) {
            // Proot variant: executable and working directory are paths or commands that
            // will resolve INSIDE the container, not on the host filesystem.
            // sandbox.sh → proot → init.sh exec "$@" handles resolution at runtime.
            // Keep the original raw value from the intent (may be "ls" for PATH lookup
            // or "/usr/bin/top" for absolute container path). Skip all host filesystem
            // canonicalization and validation.
            executionCommand.executable = executableExtra;
            // Dummy URI for the intent data field — the real executable travels
            // via EXTRA_PROOT_EXEC to avoid URI path normalization (relative
            // command names like "ls" become absolute "/ls" in the URI).
            executionCommand.executableUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE)
                .encodedPath("/proot").build();
        } else {
            // Normal Termux: resolve on host filesystem
            executionCommand.executable = TermuxFileUtils.getCanonicalPath(executionCommand.executable, null, true);

            // If executable is not a regular file, or is not readable or executable, then just return
            error = FileUtils.validateRegularFileExistenceAndPermissions("executable", executionCommand.executable, null,
                FileUtils.APP_EXECUTABLE_FILE_PERMISSIONS, true, true,
                false);
            if (error != null) {
                executionCommand.setStateFailed(error);
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
                return stopService();
            }
        }

        // If workingDirectory is not null or empty
        if (executionCommand.workingDirectory != null && !executionCommand.workingDirectory.isEmpty()) {
            if (TermuxProot.isProotVariant()) {
                // Proot variant: keep working directory as-is; resolved inside the container
                // Can be a container path like /root, /sdcard, etc.
            } else {
                // Normal Termux: resolve on host filesystem
                executionCommand.workingDirectory = TermuxFileUtils.getCanonicalPath(executionCommand.workingDirectory, null, true);

                error = TermuxFileUtils.validateDirectoryFileExistenceAndPermissions("working", executionCommand.workingDirectory,
                    true, true, true,
                    false, true);
                if (error != null) {
                    executionCommand.setStateFailed(error);
                    TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
                    return stopService();
                }
            }
        }

        if (TermuxProot.isProotVariant()) {
            // Proot variant: symlink check also applies to host filesystem — skip.
            // Use the raw executableExtra for the URI as well.
            executionCommand.executableUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executableExtra).build();
        } else {
            // If the executable passed as the extra was an applet for coreutils/busybox, then we must
            // use it instead of the canonical path above since otherwise arguments would be passed to
            // coreutils/busybox instead and command would fail. Broken symlinks would already have been
            // validated so it should be fine to use it.
            executableExtra = TermuxFileUtils.getExpandedTermuxPath(executableExtra);
            if (FileUtils.getFileType(executableExtra, false) == FileType.SYMLINK) {
                Logger.logVerbose(LOG_TAG, "The executableExtra path \"" + executableExtra + "\" is a symlink so using it instead of the canonical path \"" + executionCommand.executable + "\"");
                executionCommand.executable = executableExtra;
            }

            executionCommand.executableUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executionCommand.executable).build();
        }

        Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // Create execution intent with the action TERMUX_SERVICE#ACTION_SERVICE_EXECUTE to be sent to the TERMUX_SERVICE
        Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executionCommand.executableUri);
        execIntent.setClass(this, TermuxService.class);
        // For proot variant, the raw executable may be a relative command name ("ls") that
        // can't roundtrip through URI encoding (which always makes paths absolute "/ls").
        // Pass it as the dedicated EXTRA_PROOT_EXEC so TermuxService can use it directly.
        if (TermuxProot.isProotVariant()) {
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_PROOT_EXEC, executionCommand.executable);
        }
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, executionCommand.arguments);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_STDIN, executionCommand.stdin);
        if (executionCommand.workingDirectory != null && !executionCommand.workingDirectory.isEmpty()) execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, executionCommand.workingDirectory);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER, executionCommand.runner);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, DataUtils.getStringFromInteger(executionCommand.backgroundCustomLogLevel, null));
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, executionCommand.sessionAction);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, executionCommand.shellName);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, executionCommand.shellCreateMode);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, executionCommand.commandLabel);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, executionCommand.commandDescription);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_HELP, executionCommand.commandHelp);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, executionCommand.pluginAPIHelp);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT, executionCommand.resultConfig.resultPendingIntent);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, executionCommand.resultConfig.resultDirectoryPath);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, executionCommand.resultConfig.resultSingleFile);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, executionCommand.resultConfig.resultFileBasename);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, executionCommand.resultConfig.resultFileOutputFormat);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, executionCommand.resultConfig.resultFileErrorFormat);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, executionCommand.resultConfig.resultFilesSuffix);
        }

        // Start TERMUX_SERVICE and pass it execution intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.startForegroundService(execIntent);
        } else {
            this.startService(execIntent);
        }

        return stopService();
    }

    private int stopService() {
        runStopForeground();
        return Service.START_NOT_STICKY;
    }

    private void runStartForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotificationChannel();
            startForeground(TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_ID, buildNotification());
        }
    }

    private void runStopForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        }
    }

    private Notification buildNotification() {
        // Build the notification
        NotificationCompat.Builder builder =  NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID, NotificationCompat.PRIORITY_LOW,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME, null, null,
            null, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null)  return null;

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

}
