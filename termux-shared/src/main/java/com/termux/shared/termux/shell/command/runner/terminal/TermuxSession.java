package com.termux.shared.termux.shell.command.runner.terminal;

import android.content.Context;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Joiner;
import com.texmux.shared.R;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.shell.command.environment.UnixShellEnvironment;
import com.termux.shared.shell.command.result.ResultData;
import com.termux.shared.errors.Errno;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.termux.TermuxProot;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * A class that maintains info for foreground Termux sessions.
 * It also provides a way to link each {@link TerminalSession} with the {@link ExecutionCommand}
 * that started it.
 */
public class TermuxSession {

    private final TerminalSession mTerminalSession;
    private final ExecutionCommand mExecutionCommand;
    private final TermuxSessionClient mTermuxSessionClient;
    private final boolean mSetStdoutOnExit;

    private static final String LOG_TAG = "TermuxSession";

    // ─── Proot Session Error Types ───────────────────────────────────
    private static final String TYPE_TERMUX_SESSION = "TERMUX_SESSION";
    private static final Errno ERRNO_PROOT_BINARY_MISSING = new Errno(TYPE_TERMUX_SESSION, 1, "Proot binary is missing or not executable");
    private static final Errno ERRNO_SANDBOX_SCRIPT_MISSING = new Errno(TYPE_TERMUX_SESSION, 2, "Sandbox script is missing, proot setup may be incomplete");
    private static final Errno ERRNO_ROOTFS_NOT_EXTRACTED = new Errno(TYPE_TERMUX_SESSION, 3, "Rootfs has not been extracted");
    private static final Errno ERRNO_PROOT_TMP_DIR_FAILED = new Errno(TYPE_TERMUX_SESSION, 4, "Failed to create PROOT_TMP_DIR for session");
    private static final Errno ERRNO_APPS_BIN_DIR_MISSING = new Errno(TYPE_TERMUX_SESSION, 5, "TERMUX_APPS_BIN_DIR_PATH does not exist");

    private TermuxSession(@NonNull final TerminalSession terminalSession, @NonNull final ExecutionCommand executionCommand,
                          final TermuxSessionClient termuxSessionClient, final boolean setStdoutOnExit) {
        this.mTerminalSession = terminalSession;
        this.mExecutionCommand = executionCommand;
        this.mTermuxSessionClient = termuxSessionClient;
        this.mSetStdoutOnExit = setStdoutOnExit;
    }

    /**
     * Start execution of an {@link ExecutionCommand} with {@link Runtime#exec(String[], String[], File)}.
     *
     * The {@link ExecutionCommand#executable}, must be set, {@link ExecutionCommand#commandLabel},
     * {@link ExecutionCommand#arguments} and {@link ExecutionCommand#workingDirectory} may optionally
     * be set.
     *
     * If {@link ExecutionCommand#executable} is {@code null}, then a default shell is automatically
     * chosen.
     *
     * @param currentPackageContext The {@link Context} for operations. This must be the context for
     *                              the current package and not the context of a `sharedUserId` package,
     *                              since environment setup may be dependent on current package.
     * @param executionCommand The {@link ExecutionCommand} containing the information for execution command.
     * @param terminalSessionClient The {@link TerminalSessionClient} interface implementation.
     * @param termuxSessionClient The {@link TermuxSessionClient} interface implementation.
     * @param shellEnvironmentClient The {@link IShellEnvironment} interface implementation.
     * @param additionalEnvironment The additional shell environment variables to export. Existing
     *                              variables will be overridden.
     * @param setStdoutOnExit If set to {@code true}, then the {@link ResultData#stdout}
     *                        available in the {@link TermuxSessionClient#onTermuxSessionExited(TermuxSession)}
     *                        callback will be set to the {@link TerminalSession} transcript. The session
     *                        transcript will contain both stdout and stderr combined, basically
     *                        anything sent to the the pseudo terminal /dev/pts, including PS1 prefixes.
     *                        Set this to {@code true} only if the session transcript is required,
     *                        since this requires extra processing to get it.
     * @return Returns the {@link TermuxSession}. This will be {@code null} if failed to start the execution command.
     */
    public static TermuxSession execute(@NonNull final Context currentPackageContext, @NonNull ExecutionCommand executionCommand,
                                        @NonNull final TerminalSessionClient terminalSessionClient, final TermuxSessionClient termuxSessionClient,
                                        @NonNull final IShellEnvironment shellEnvironmentClient,
                                        @Nullable HashMap<String, String> additionalEnvironment,
                                        final boolean setStdoutOnExit) {
        if (executionCommand.executable != null && executionCommand.executable.isEmpty())
            executionCommand.executable = null;
        if (executionCommand.workingDirectory == null || executionCommand.workingDirectory.isEmpty())
            executionCommand.workingDirectory = shellEnvironmentClient.getDefaultWorkingDirectoryPath();
        if (executionCommand.workingDirectory.isEmpty())
            executionCommand.workingDirectory = "/";

        String defaultBinPath = shellEnvironmentClient.getDefaultBinPath();
        if (defaultBinPath.isEmpty())
            defaultBinPath = "/system/bin";

        boolean isLoginShell = false;
        boolean wasExecutableNull = (executionCommand.executable == null);
        if (executionCommand.executable == null) {
            if (!executionCommand.isFailsafe) {
                for (String shellBinary : UnixShellEnvironment.LOGIN_SHELL_BINARIES) {
                    File shellFile = new File(defaultBinPath, shellBinary);
                    if (shellFile.canExecute()) {
                        executionCommand.executable = shellFile.getAbsolutePath();
                        break;
                    }
                }
            }

            if (executionCommand.executable == null) {
                // Fall back to system shell as last resort:
                // Do not start a login shell since ~/.profile may cause startup failure if its invalid.
                // /system/bin/sh is provided by mksh (not toybox) and does load .mkshrc but for android its set
                // to /system/etc/mkshrc even though its default is ~/.mkshrc.
                // So /system/etc/mkshrc must still be valid for failsafe session to start properly.
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=663
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/src/main.c;l=41
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r3:external/mksh/Android.bp;l=114
                executionCommand.executable = "/system/bin/sh";
            } else {
                isLoginShell = true;
            }

        }

        if (TermuxProot.isProotVariant() && !executionCommand.isFailsafe && executionCommand.executable != null) {
            try {
                // Check that TERMUX_APPS_BIN_DIR_PATH exists before proceeding
                File appsBinDir = new File(TermuxConstants.TERMUX_APPS_BIN_DIR_PATH);
                if (!appsBinDir.isDirectory()) {
                    String errMsg = currentPackageContext.getString(R.string.proot_setup_error_deployment_verification);
                    Logger.logError(LOG_TAG, "TERMUX_APPS_BIN_DIR_PATH not found at "
                        + TermuxConstants.TERMUX_APPS_BIN_DIR_PATH);
                    executionCommand.setStateFailed(ERRNO_APPS_BIN_DIR_MISSING.getCode(),
                        ERRNO_APPS_BIN_DIR_MISSING.getMessage() + ": " + errMsg);
                    TermuxSession.processTermuxSessionResult(null, executionCommand);
                    return null;
                }

                // Verify sandbox script exists before trying to run it
                String sandboxPath = TermuxConstants.TERMUX_APPS_BIN_DIR_PATH + "/sandbox";
                File sandboxFile = new File(sandboxPath);
                if (!sandboxFile.isFile()) {
                    String errMsg = "Sandbox script not found at " + sandboxPath
                        + " — has the proot setup completed?";
                    Logger.logError(LOG_TAG, errMsg);
                    executionCommand.setStateFailed(ERRNO_SANDBOX_SCRIPT_MISSING.getCode(), errMsg);
                    TermuxSession.processTermuxSessionResult(null, executionCommand);
                    return null;
                }

                // Check rootfs is extracted (has marker)
                File rootfsMarker = new File(TermuxProot.ROOTFS_DIR_PATH + "/.extracted");
                if (!rootfsMarker.isFile()) {
                    Logger.logWarn(LOG_TAG, "Rootfs marker not found at " + rootfsMarker.getAbsolutePath()
                        + " — rootfs may not be extracted yet");
                }

                // wasExecutableNull distinguishes interactive terminal (originally null, auto-detected shell)
                // from RUN_COMMAND (originally set by intent extras). Only the latter should pass the
                // original command/args through to sandbox.sh → init.sh → exec "$@".
                if (!wasExecutableNull) {
                    // RUN_COMMAND or explicit command — pass original command+args to sandbox
                    // so init.sh inside the container can exec "$@".
                    String origExec = executionCommand.executable;
                    String[] origArgs = executionCommand.arguments;
                    executionCommand.executable = "/system/bin/sh";
                    StringBuilder cmd = new StringBuilder();
                    cmd.append(TermuxConstants.TERMUX_APPS_BIN_DIR_PATH).append("/sandbox");
                    cmd.append(" '").append(origExec.replace("'", "'\\''")).append("'");
                    if (origArgs != null) {
                        for (String arg : origArgs) {
                            if (arg != null) {
                                cmd.append(" '").append(arg.replace("'", "'\\''")).append("'");
                            }
                        }
                    }
                    executionCommand.arguments = new String[]{ "-c", cmd.toString() };
                } else {
                    // Interactive terminal session — sandbox without args.
                    // init.sh runs interactive bash when $# == 0.
                    executionCommand.executable = "/system/bin/sh";
                    executionCommand.arguments = new String[]{ "-c", TermuxConstants.TERMUX_APPS_BIN_DIR_PATH + "/sandbox" };
                }
            } catch (Exception e) {
                // Catch any unexpected error during proot command setup
                String errMsg = "Unexpected error during proot session setup: " + e.getMessage();
                Logger.logError(LOG_TAG, errMsg);
                executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errMsg);
                TermuxSession.processTermuxSessionResult(null, executionCommand);
                return null;
            }
        }

        // Pre-create fake /proc files so sandbox.sh _fb() fallback skips them
        if (TermuxProot.isProotVariant()) {
            String sysDataDirPath = TermuxConstants.TERMUX_FILES_DIR_PATH + "/.sysdata";
            File sysDataDir = new File(sysDataDirPath);
            if (!sysDataDir.isDirectory()) {
                sysDataDir.mkdirs();
            }

            createFakeProcFileIfNotExists(sysDataDir, "stat", "cpu  0 0 0 0 0 0 0 0 0 0\n");
            createFakeProcFileIfNotExists(sysDataDir, "vmstat", "nr_free_pages 0\n");
            createFakeProcFileIfNotExists(sysDataDir, "loadavg", "0.00 0.00 0.00 1/1 1\n");
            createFakeProcFileIfNotExists(sysDataDir, "uptime", "0.00 0.00\n");
            createFakeProcFileIfNotExists(sysDataDir, "version", "Linux version 5.10.0-android (builduser@host) (Android clang)\n");
            createFakeProcFileIfNotExists(sysDataDir, "sysctl_entry_cap_last_cap", "39\n");
            createFakeProcFileIfNotExists(sysDataDir, "sysctl_inotify_max_user_watches", "65536\n");
            createFakeProcFileIfNotExists(sysDataDir, "sysctl_kernel_overflowuid", "65534\n");
            createFakeProcFileIfNotExists(sysDataDir, "sysctl_kernel_overflowgid", "65534\n");
        }

        // Setup command args
        String[] commandArgs = shellEnvironmentClient.setupShellCommandArguments(executionCommand.executable, executionCommand.arguments);

        executionCommand.executable = commandArgs[0];
        String processName = (isLoginShell ? "-" : "") + ShellUtils.getExecutableBasename(executionCommand.executable);

        String[] arguments = new String[commandArgs.length];
        arguments[0] = processName;
        if (commandArgs.length > 1) System.arraycopy(commandArgs, 1, arguments, 1, commandArgs.length - 1);

        executionCommand.arguments = arguments;

        if (executionCommand.commandLabel == null)
            executionCommand.commandLabel = processName;

        // Setup command environment
        HashMap<String, String> environment = shellEnvironmentClient.setupShellCommandEnvironment(currentPackageContext,
            executionCommand);
        if (additionalEnvironment != null)
            environment.putAll(additionalEnvironment);

        // Per-session PROOT_TMP_DIR — sandbox.sh fallback kalo gak diset dari sini
        if (TermuxProot.isProotVariant()) {
            String sessionTmpDir = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH + "/.proot-session-" + executionCommand.id;
            File sessionTmpDirFile = new File(sessionTmpDir);
            if (!sessionTmpDirFile.mkdirs() && !sessionTmpDirFile.isDirectory()) {
                String errMsg = "Failed to create PROOT_TMP_DIR at " + sessionTmpDir;
                Logger.logError(LOG_TAG, errMsg);
                executionCommand.setStateFailed(ERRNO_PROOT_TMP_DIR_FAILED.getCode(), errMsg);
                TermuxSession.processTermuxSessionResult(null, executionCommand);
                return null;
            }
            environment.put("PROOT_TMP_DIR", sessionTmpDir);
        }

        List<String> environmentList = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment);
        Collections.sort(environmentList);
        String[] environmentArray = environmentList.toArray(new String[0]);

        if (!executionCommand.setState(ExecutionCommand.ExecutionState.EXECUTING)) {
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), currentPackageContext.getString(R.string.error_failed_to_execute_termux_session_command, executionCommand.getCommandIdAndLabelLogString()));
            TermuxSession.processTermuxSessionResult(null, executionCommand);
            return null;
        }

        Logger.logDebugExtended(LOG_TAG, executionCommand.toString());
        Logger.logVerboseExtended(LOG_TAG, "\"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession Environment:\n" +
            Joiner.on("\n").join(environmentArray));

        Logger.logDebug(LOG_TAG, "Running \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession");
        TerminalSession terminalSession = new TerminalSession(executionCommand.executable,
            executionCommand.workingDirectory, executionCommand.arguments, environmentArray,
            executionCommand.terminalTranscriptRows, terminalSessionClient);

        if (executionCommand.shellName != null) {
            terminalSession.mSessionName = executionCommand.shellName;
        }

        return new TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit);
    }

    /**
     * Signal that this {@link TermuxSession} has finished.  This should be called when
     * {@link TerminalSessionClient#onSessionFinished(TerminalSession)} callback is received by the caller.
     *
     * If the processes has finished, then sets {@link ResultData#stdout}, {@link ResultData#stderr}
     * and {@link ResultData#exitCode} for the {@link #mExecutionCommand} of the {@code termuxTask}
     * and then calls {@link #processTermuxSessionResult(TermuxSession, ExecutionCommand)} to process the result}.
     *
     */
    public void finish() {
        // If process is still running, then ignore the call
        if (mTerminalSession.isRunning()) return;

        int exitCode = mTerminalSession.getExitStatus();

        if (exitCode == 0)
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession exited normally");
        else
            Logger.logDebug(LOG_TAG, "The \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession exited with code: " + exitCode);

        // If the execution command has already failed, like SIGKILL was sent, then don't continue
        if (mExecutionCommand.isStateFailed()) {
            Logger.logDebug(LOG_TAG, "Ignoring setting \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession state to ExecutionState.EXECUTED and processing results since it has already failed");
            return;
        }

        mExecutionCommand.resultData.exitCode = exitCode;

        if (this.mSetStdoutOnExit)
            mExecutionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(mTerminalSession, true, false));

        if (!mExecutionCommand.setState(ExecutionCommand.ExecutionState.EXECUTED))
            return;

        TermuxSession.processTermuxSessionResult(this, null);
    }

    /**
     * Kill this {@link TermuxSession} by sending a {@link OsConstants#SIGILL} to its {@link #mTerminalSession}
     * if its still executing.
     *
     * @param context The {@link Context} for operations.
     * @param processResult If set to {@code true}, then the {@link #processTermuxSessionResult(TermuxSession, ExecutionCommand)}
     *                      will be called to process the failure.
     */
    public void killIfExecuting(@NonNull final Context context, boolean processResult) {
        // If execution command has already finished executing, then no need to process results or send SIGKILL
        if (mExecutionCommand.hasExecuted()) {
            Logger.logDebug(LOG_TAG, "Ignoring sending SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession since it has already finished executing");
            return;
        }

        Logger.logDebug(LOG_TAG, "Send SIGKILL to \"" + mExecutionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession");
        if (mExecutionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), context.getString(R.string.error_sending_sigkill_to_process))) {
            if (processResult) {
                mExecutionCommand.resultData.exitCode = 137; // SIGKILL

                // Get whatever output has been set till now in case its needed
                if (this.mSetStdoutOnExit)
                    mExecutionCommand.resultData.stdout.append(ShellUtils.getTerminalSessionTranscriptText(mTerminalSession, true, false));

                TermuxSession.processTermuxSessionResult(this, null);
            }
        }

        // Send SIGKILL to process
        mTerminalSession.finishIfRunning();
    }

    /**
     * Process the results of {@link TermuxSession} or {@link ExecutionCommand}.
     *
     * Only one of {@code termuxSession} and {@code executionCommand} must be set.
     *
     * If the {@code termuxSession} and its {@link #mTermuxSessionClient} are not {@code null},
     * then the {@link TermuxSession.TermuxSessionClient#onTermuxSessionExited(TermuxSession)}
     * callback will be called.
     *
     * @param termuxSession The {@link TermuxSession}, which should be set if
     *                  {@link #execute(Context, ExecutionCommand, TerminalSessionClient, TermuxSessionClient, IShellEnvironment, HashMap, boolean)}
     *                   successfully started the process.
     * @param executionCommand The {@link ExecutionCommand}, which should be set if
     *                          {@link #execute(Context, ExecutionCommand, TerminalSessionClient, TermuxSessionClient, IShellEnvironment, HashMap, boolean)}
     *                          failed to start the process.
     */
    private static void processTermuxSessionResult(final TermuxSession termuxSession, ExecutionCommand executionCommand) {
        if (termuxSession != null)
            executionCommand = termuxSession.mExecutionCommand;

        if (executionCommand == null) return;

        if (executionCommand.shouldNotProcessResults()) {
            Logger.logDebug(LOG_TAG, "Ignoring duplicate call to process \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession result");
            return;
        }

        Logger.logDebug(LOG_TAG, "Processing \"" + executionCommand.getCommandIdAndLabelLogString() + "\" TermuxSession result");

        if (termuxSession != null && termuxSession.mTermuxSessionClient != null) {
            termuxSession.mTermuxSessionClient.onTermuxSessionExited(termuxSession);
        } else {
            // If a callback is not set and execution command didn't fail, then we set success state now
            // Otherwise, the callback host can set it himself when its done with the termuxSession
            if (!executionCommand.isStateFailed())
                executionCommand.setState(ExecutionCommand.ExecutionState.SUCCESS);
        }
    }

    public TerminalSession getTerminalSession() {
        return mTerminalSession;
    }

    public ExecutionCommand getExecutionCommand() {
        return mExecutionCommand;
    }



    /** Create a fake proc file in .sysdata so sandbox.sh _fb() fallback skips it. */
    private static void createFakeProcFileIfNotExists(File dir, String name, String content) {
        File file = new File(dir, name);
        if (!file.exists()) {
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                try {
                    fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } finally {
                    fos.close();
                }
            } catch (java.io.IOException e) {
                Logger.logError(LOG_TAG, "Failed to create " + name + ": " + e.getMessage());
            }
        }
    }

    public interface TermuxSessionClient {

        /**
         * Callback function for when {@link TermuxSession} exits.
         *
         * @param termuxSession The {@link TermuxSession} that exited.
         */
        void onTermuxSessionExited(TermuxSession termuxSession);

    }

}
