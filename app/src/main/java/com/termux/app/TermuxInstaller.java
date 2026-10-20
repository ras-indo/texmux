package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.system.Os;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Typeface;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.errors.Errno;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
//import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR_PATH;

/**
 * Bootstrap the PRoot Alpine container — re:Terminal compatible approach.
 * <p>
 * Instead of extracting the rootfs tarball in Java (which loses file permissions),
 * we deploy the tarball as {@code files/rootfs.tar.gz} and let the
 * {@code sandbox.sh} script extract it at runtime via {@code tar -xf}, preserving
 * permissions exactly as Alpine ships them.
 * <p>
 * All host-side binaries and scripts are deployed directly under
 * {@code files/} — no {@code usr/} prefix:
 * <pre>
 *   files/
 *     apps/bin/        ← binaries (proot, am-socket) and shell scripts
 *     rootfs.tar.gz    ← rootfs archive
 *     tmp/             ← fake /proc files, temp dirs
 *     .termux/         ← termux.properties (read by Termux app)
 * </pre>
 * (1) If essential artifacts exist (apps/bin/sandbox, apps/bin/proot, rootfs.tar.gz), skip.
 * (2) Delete and re-create the target paths.
 * (3) Deploy everything directly (no staging + rename).
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    // ─── New layout paths (relative to TERMUX_FILES_DIR) ─────────────
    private static final String APPS_BIN = "apps/bin";
    private static final String APPS_COM_TEXMUX = "apps/com.termux";
    private static final String TMP_DIR = "tmp";
    private static final String ROOTFS_TARBALL = "rootfs.tar.gz";

    // ─── Proot Setup Error Types ───────────────────────────────────────
    private static final String TYPE_PROOT_SETUP = "PROOT_SETUP";
    private static final Errno ERRNO_ASSETS_MISSING = new Errno(TYPE_PROOT_SETUP, 1, "Required assets missing from app package");
    private static final Errno ERRNO_DISK_SPACE = new Errno(TYPE_PROOT_SETUP, 2, "Insufficient disk space");
    private static final Errno ERRNO_PROOT_BINARY_MISSING = new Errno(TYPE_PROOT_SETUP, 3, "Proot binary missing or not executable");
    private static final Errno ERRNO_PROOT_BINARY_FAILED = new Errno(TYPE_PROOT_SETUP, 4, "Proot binary verification failed");
    private static final Errno ERRNO_ROOTFS_CORRUPTED = new Errno(TYPE_PROOT_SETUP, 5, "Rootfs extraction corrupted");
    private static final Errno ERRNO_EXTRACTION_TIMEOUT = new Errno(TYPE_PROOT_SETUP, 6, "Rootfs extraction timed out");
    private static final Errno ERRNO_EXTRACTION_FAILED = new Errno(TYPE_PROOT_SETUP, 7, "Rootfs extraction failed");
    private static final Errno ERRNO_DEPLOYMENT_FAILED = new Errno(TYPE_PROOT_SETUP, 8, "Deployment verification failed");
    private static final Errno ERRNO_SANDBOX_SCRIPT_MISSING = new Errno(TYPE_PROOT_SETUP, 9, "Sandbox script is missing from app assets");

    /** Extract container rootfs if it has not been extracted yet. */
    static void setupContainerIfNeeded(final Activity activity, final Runnable whenDone) {
        String setupErrorMessage;
        Error filesDirectoryAccessibleError;

        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            setupErrorMessage = activity.getString(R.string.proot_setup_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_FILES_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, setupErrorMessage);
            sendSetupCrashReportNotification(activity, setupErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.proot_setup_error_title),
                setupErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            setupErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                setupErrorMessage += "\n\n" + activity.getString(R.string.proot_setup_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_FILES_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, setupErrorMessage);
            sendSetupCrashReportNotification(activity, setupErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.proot_setup_error_title),
                setupErrorMessage, null);
            return;
        }

        // Check if essential artifacts already exist
        if (checkEssentialArtifacts()) {
            try {
                deployMissingBinaries(activity);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "deployMissingBinaries failed: " + e.getMessage());
            }
            try {
                deployTermuxApiPackage(activity);
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "deployTermuxApiPackage failed: " + e.getMessage());
            }
            try {
                deployShellScripts(activity); // Always deploy scripts to pick up updates
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "deployShellScripts failed: " + e.getMessage());
                setupContainerIfNeeded(activity, whenDone);
                return;
            }
            // Best-effort rootfs extraction in background — if it fails,
            // sandbox.sh will extract on session start (as before).
            java.util.function.Consumer<String> noop = line -> {};
            new Thread(() -> {
                try {
                    extractRootfs(activity, noop);
                } catch (Exception e) {
                    Logger.logWarn(LOG_TAG, "Background rootfs extraction failed: " + e.getMessage());
                }
            }).start();
            whenDone.run();
            return;
        }

        // ── Live log dialog ────────────────────────────────────────────────────────────
        View dialogLayout = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_setup_container, null);

        TextView logView = dialogLayout.findViewById(R.id.setup_log_view);
        ScrollView scrollView = dialogLayout.findViewById(R.id.setup_log_scroller);

        final AlertDialog setupDialog = new AlertDialog.Builder(activity, R.style.TermuxSetupDialogStyle)
            .setView(dialogLayout)
            .setCancelable(false)
            .show();

        // Transparent window background so the glass card drawable shows through
        setupDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // API 31+ glass blur behind dialog
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setupDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        java.util.function.Consumer<String> log = line -> activity.runOnUiThread(() -> {
            logView.append(line + "\n");
            scrollView.fullScroll(View.FOCUS_DOWN);
        });

        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Deploying container assets to \"" + TERMUX_FILES_DIR_PATH + "\".");

                    // ── Pre-flight checks ──────────────────────────────────────────────
                    // 1. Verify all required source assets exist in the APK
                    log.accept("Checking assets...");
                    Error assetsError = checkAssetsExist(activity);
                    if (assetsError != null) {
                        String msg = activity.getString(R.string.proot_setup_error_missing_assets);
                        Logger.logError(LOG_TAG, msg);
                        log.accept("  ERROR: Missing assets");
                        throw new Exception(msg);
                    }

                    // 2. Check available disk space before deploying (at least 2 GB)
                    log.accept("Checking disk space...");
                    String diskError = checkDiskSpace(2048);
                    if (diskError != null) {
                        String msg = activity.getString(R.string.proot_setup_error_disk_space,
                            MarkdownUtils.getMarkdownCodeForString(TERMUX_FILES_DIR_PATH, false));
                        Logger.logError(LOG_TAG, diskError);
                        log.accept("  ERROR: " + diskError);
                        throw new Exception(msg);
                    }

                    log.accept("Deploying rootfs tarball...");
                    deployRootfsTarball(activity);
                    log.accept("Deploying shell scripts...");
                    deployShellScripts(activity);
                    log.accept("Deploying termux.properties...");
                    deployTermuxProperties(activity);
                    log.accept("Deploying proot binary...");
                    deployProotBinaries(activity);
                    log.accept("Deploying Termux:API package...");
                    deployTermuxApiPackage(activity);
                    log.accept("Creating fake /proc files...");
                    createFakeProcFiles();
                    log.accept("Extracting rootfs...");
                    extractRootfs(activity, log);

                    // Verify all deployed artifacts are valid
                    log.accept("Verifying deployment...");
                    Error verifyError = verifyDeployment();
                    if (verifyError != null) {
                        String msg = activity.getString(R.string.proot_setup_error_deployment_verification);
                        Logger.logError(LOG_TAG, "Deployment verification failed: " + verifyError.getMessage());
                        log.accept("  ERROR: Deployment verification failed");
                        throw new Exception(msg);
                    }

                    Logger.logInfo(LOG_TAG, "Container asset deployment complete.");
                    log.accept("Done.");

                    activity.runOnUiThread(whenDone);

                } catch (final RuntimeException e) {
                    // RuntimeException (e.g. SecurityException on statfs) — show user-friendly error
                    String msg = "Unexpected error during setup: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage();
                    Logger.logError(LOG_TAG, msg);
                    showSetupErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(
                        null, Logger.getStackTracesStringArray(e)));

                } catch (final Exception e) {
                    // Determine if this is a rootfs extraction failure that qualifies for clean retry
                    String errMsg = e.getMessage();
                    boolean isExtractionError = errMsg != null && (errMsg.contains("Rootfs extraction")
                        || errMsg.contains("rootfs")
                        || errMsg.contains(activity.getString(R.string.proot_setup_error_rootfs_not_extracted))
                        || errMsg.contains(activity.getString(R.string.proot_setup_error_rootfs_corrupted)));
                    if (isExtractionError) {
                        showSetupErrorDialog(activity, whenDone,
                            Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                            true, () -> {
                                // Clean retry: delete ROOTFS and marker, then retry
                                File rootfsDir = new File(TERMUX_FILES_DIR_PATH, "ROOTFS");
                                Logger.logInfo(LOG_TAG, "Clean retry requested — deleting ROOTFS directory");
                                deleteDirectoryRecursive(rootfsDir);
                                setupContainerIfNeeded(activity, whenDone);
                            });
                    } else {
                        showSetupErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(
                            null, Logger.getStackTracesStringArray(e)));
                    }

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            setupDialog.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    /** Check all essential deployment artifacts exist. */
    private static boolean checkEssentialArtifacts() {
        String[] paths = {APPS_BIN + "/sandbox", APPS_BIN + "/proot", APPS_BIN + "/init", ROOTFS_TARBALL,
            APPS_COM_TEXMUX + "/libexec/termux-api-broadcast"};
        boolean allPresent = true;
        for (String path : paths) {
            File f = new File(TERMUX_FILES_DIR_PATH + "/" + path);
            if (!f.isFile()) {
                allPresent = false;
                Logger.logInfo(LOG_TAG, "Essential artifact missing: " + path);
            }
        }
        return allPresent;
    }

    /** Check that all required source asset files exist in the APK before deployment. */
    private static Error checkAssetsExist(Activity activity) {
        String[] assetPaths = {
            "rootfs/rootfs.tar",
            "terminal/sandbox.sh",
            "terminal/init.sh",
            "proot/proot",
            "am-socket/termux-am-socket"
        };
        for (String path : assetPaths) {
            try (InputStream in = activity.getAssets().open(path)) {
                // Asset exists — close and continue
            } catch (java.io.IOException e) {
                String msg = "Required asset \"" + path + "\" is missing from the app package."
                    + " This may indicate a corrupted installation.";
                Logger.logError(LOG_TAG, msg);
                return ERRNO_ASSETS_MISSING.getError("Asset \"" + path + "\" not found in APK assets");
            }
        }
        return null;
    }

    /**
     * Show a setup error dialog with optional "Delete and retry" for extraction failures.
     * @param cleanRetryAvailable If true, adds a third button to delete ROOTFS and clean-retry.
     */
    public static void showSetupErrorDialog(Activity activity, Runnable whenDone, String message) {
        showSetupErrorDialog(activity, whenDone, message, false, null);
    }

    public static void showSetupErrorDialog(Activity activity, Runnable whenDone, String message,
                                            boolean cleanRetryAvailable,
                                            @androidx.annotation.Nullable Runnable cleanRetryAction) {
        Logger.logErrorExtended(LOG_TAG, "Container Setup Error:\n" + message);

        sendSetupCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                TextView textView = new TextView(activity);
                textView.setText(message);
                textView.setTextIsSelectable(true);
                textView.setTypeface(Typeface.MONOSPACE);
                textView.setPadding(48, 24, 48, 24);
                textView.setTextSize(13);

                ScrollView scrollView = new ScrollView(activity);
                scrollView.addView(textView);

                AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setTitle(R.string.proot_setup_error_title)
                    .setView(scrollView)
                    .setNegativeButton(R.string.proot_setup_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.proot_setup_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        TermuxInstaller.setupContainerIfNeeded(activity, whenDone);
                    });
                if (cleanRetryAvailable && cleanRetryAction != null) {
                    builder.setNeutralButton(R.string.proot_setup_error_retry_clean, (dialog, which) -> {
                        dialog.dismiss();
                        cleanRetryAction.run();
                    });
                }
                builder.show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendSetupCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Error";

        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    // ─── Deploy rootfs tarball ─────────────────────────────────────────────────────────────
    // The tarball is deployed as `files/rootfs.tar.gz` and extracted at runtime
    // by sandbox.sh via `tar -xf`. This preserves file permissions that Java tar extraction
    // loses.
    //
    // Handle both gzip and raw tar: AAPT2 may or may not decompress .gz in assets.
    // We auto-detect by peeking at the first two bytes (gzip magic = 0x1f 0x8b).
    private static void deployRootfsTarball(Activity activity) throws Exception {
        try (InputStream rawIn = activity.getAssets().open("rootfs/rootfs.tar");
             BufferedInputStream bin = new BufferedInputStream(rawIn)) {
            bin.mark(2);
            int b1 = bin.read();
            int b2 = bin.read();
            bin.reset();

            InputStream dataIn = (b1 == 0x1f && b2 == 0x8b)
                ? new java.util.zip.GZIPInputStream(bin)
                : bin;

            File dst = new File(TERMUX_FILES_DIR_PATH, ROOTFS_TARBALL);
            try (InputStream in = dataIn;
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        }
        Logger.logInfo(LOG_TAG, "Deployed rootfs.tar.gz to " + TERMUX_FILES_DIR_PATH + "/" + ROOTFS_TARBALL);
    }

    // ─── Deploy shell scripts ──────────────────────────────────────────────────────────────
    //   sandbox  → apps/bin/sandbox   (host-side entry point, run by /system/bin/sh)
    //   init     → apps/bin/init      (container init, executed inside proot)
    //   setup    → apps/bin/setup     (post-extraction container setup)
    //   utils    → apps/bin/utils     (shared shell functions)
    private static void deployShellScripts(Activity activity) throws Exception {
        File appsBin = new File(TERMUX_FILES_DIR_PATH, APPS_BIN);
        appsBin.mkdirs();

        extractAsset(activity, "terminal/sandbox.sh", new File(appsBin, "sandbox"), true);
        extractAsset(activity, "terminal/init.sh", new File(appsBin, "init"), true);
        extractAsset(activity, "terminal/init.sh", new File(appsBin, "init.sh"), true);
        extractAsset(activity, "terminal/setup.sh", new File(appsBin, "setup"), true);
        extractAsset(activity, "terminal/utils", new File(appsBin, "utils"), true);

        // Deploy the Java-based termux-api client (replaces the C binary for direct socket communication).
        // This is extracted as termux-api-client so it doesn't collide with the C binary at $PREFIX/libexec/termux-api.
        extractAsset(activity, "terminal/termux-api", new File(appsBin, "termux-api"), true);

        extractAsset(activity, "android-shell/android", new File(appsBin, "android"), true);

        Logger.logInfo(LOG_TAG, "Deployed shell scripts to " + APPS_BIN + "/");
    }

    // ─── Deploy termux.properties ───────────────────────────────────────────────────────────
    private static void deployTermuxProperties(Activity activity) throws Exception {
        File termuxDotdir = new File(TERMUX_FILES_DIR_PATH, "/.termux");
        termuxDotdir.mkdirs();
        File target = new File(termuxDotdir, "termux.properties");
        if (!target.exists()) {
            extractAsset(activity, "termux-properties/termux.properties", target, false);
        }
        Logger.logInfo(LOG_TAG, "Deployed termux.properties to " + target.getAbsolutePath());
    }

    private static void extractAsset(Activity activity, String assetPath, File target, boolean executable) throws Exception {
        try (InputStream in = activity.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        if (executable) {
            target.setExecutable(true, false);
        }
        Logger.logInfo(LOG_TAG, "Extracted asset " + assetPath + " to " + target.getAbsolutePath());
    }

    /** Deploy a termux-api binary from assets, tolerating FileNotFoundException for incremental builds. */
    private static void deployTermuxApiBinary(Activity activity, String assetPath, File dir, String name) {
        try {
            extractAsset(activity, assetPath, new File(dir, name), true);
        } catch (java.io.FileNotFoundException e) {
            Logger.logDebug(LOG_TAG, "termux-api binary not found in assets, skipping: " + assetPath);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to deploy " + name + ": " + e.getMessage());
        }
    }

    // ─── Deploy proot, am-socket, and termux-am ──────────────────────────────────────────
    //   proot              → apps/bin/proot      (static talloc, Bionic-linked)
   // termux-am-socket   → apps/com.termux/bin/termux-am-socket  (static, for AM socket inside proot)
   // termux-am          → apps/com.termux/bin/termux-am         (wrapper script, calls termux-am-socket)
    //   libproot-loader.so → via jniLibs, auto-extracted by Android to nativeLibraryDir
    private static void deployProotBinaries(Activity activity) throws Exception {
        File appsBin = new File(TERMUX_FILES_DIR_PATH, APPS_BIN);
        appsBin.mkdirs();

        // proot binary
        try (InputStream in = activity.getAssets().open("proot/proot")) {
            File dst = new File(appsBin, "proot");
            try (FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            dst.setExecutable(true, false);
            Logger.logInfo(LOG_TAG, "Deployed proot to " + dst.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            Logger.logWarn(LOG_TAG, "proot binary not found in assets/proot/, skipping");
        }

        // termux-am-socket — static binary for AM socket inside proot
        File comTexmuxBin = new File(TERMUX_FILES_DIR_PATH, APPS_COM_TEXMUX + "/bin");
        comTexmuxBin.mkdirs();
        try (InputStream in = activity.getAssets().open("am-socket/termux-am-socket")) {
            File dst = new File(comTexmuxBin, "termux-am-socket");
            try (FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            dst.setExecutable(true, false);
            Logger.logInfo(LOG_TAG, "Deployed termux-am-socket to " + dst.getAbsolutePath());
            // am-socket symlink → termux-am-socket for backward compat
            new java.io.File(comTexmuxBin, "am-socket").delete();
            java.nio.file.Files.createSymbolicLink(
                new java.io.File(comTexmuxBin, "am-socket").toPath(),
                new java.io.File("termux-am-socket").toPath());
            Logger.logInfo(LOG_TAG, "Created am-socket symlink → termux-am-socket in " + comTexmuxBin);
        } catch (java.io.FileNotFoundException e) {
            Logger.logWarn(LOG_TAG, "termux-am-socket not found in assets/am-socket/, skipping");
        }

        // termux-am — shell wrapper that calls termux-am-socket
        try (InputStream in = activity.getAssets().open("am-socket/termux-am")) {
            File dst = new File(comTexmuxBin, "termux-am");
            try (FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            dst.setExecutable(true, false);
            Logger.logInfo(LOG_TAG, "Deployed termux-am to " + dst.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            Logger.logWarn(LOG_TAG, "termux-am not found in assets/am-socket/, skipping");
        }

        // am.apk — for "am" via app_process (like termux original)
        File comTexmuxLibexec = new File(TERMUX_FILES_DIR_PATH, APPS_COM_TEXMUX + "/libexec");
        comTexmuxLibexec.mkdirs();
        try (InputStream in = activity.getAssets().open("am-socket/am.apk")) {
            File dst = new File(comTexmuxLibexec, "am.apk");
            try (FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            dst.setReadable(true, false);
            Logger.logInfo(LOG_TAG, "Deployed am.apk to " + dst.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            Logger.logWarn(LOG_TAG, "am.apk not found in assets/am-socket/, skipping");
        }

        // Verify proot binary runs correctly after deployment
        String prootVerifyError = verifyProotBinary();
        if (prootVerifyError != null) {
            Logger.logError(LOG_TAG, "Post-deployment proot verification failed: " + prootVerifyError);
            throw new Exception(prootVerifyError);
        }
    }

    // ─── Create fake /proc files ──────────────────────────────────────────────────────────
    // These files are bind-mounted by sandbox.sh to satisfy Alpine processes
    // that read /proc/stat and /proc/vmstat without hanging on the real proc.
    private static void createFakeProcFiles() throws Exception {
        File tmpDir = new File(TERMUX_FILES_DIR_PATH, TMP_DIR);
        tmpDir.mkdirs();

        java.nio.file.Files.write(new File(tmpDir, "stat").toPath(), "cpu  0 0 0 0 0 0 0 0 0 0\n".getBytes());
        java.nio.file.Files.write(new File(tmpDir, "vmstat").toPath(), "nr_free_pages 0\n".getBytes());

        Logger.logInfo(LOG_TAG, "Created fake /proc files at " + tmpDir.getAbsolutePath());
    }

    /** Recursively delete a file or directory. Does nothing if the file does not exist. */
    private static void deleteDirectoryRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryRecursive(child);
                }
            }
        }
        file.delete();
    }

    /**
     * Check that the files directory has at least {@code requiredMegabytes} free.
     * Returns null if OK, or an error message string if insufficient.
     */
    private static String checkDiskSpace(long requiredMegabytes) {
        StatFs stat = new StatFs(TERMUX_FILES_DIR_PATH);
        long availableBytes = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        long requiredBytes = requiredMegabytes * 1024L * 1024L;
        if (availableBytes < requiredBytes) {
            long availableMb = availableBytes / (1024L * 1024L);
            return "Insufficient disk space: " + availableMb + " MB available, "
                + requiredMegabytes + " MB required";
        }
        return null;
    }

    /** Return true if the rootfs has the expected well-known files (e.g. /etc/passwd, /bin/sh). */
    private static boolean verifyRootfs(File rootfsDir) {
        return new File(rootfsDir, "etc/passwd").isFile()
            && new File(rootfsDir, "bin/sh").exists();
    }

    /** Run proot --version to confirm the binary executes. Returns null on success, error string on failure. */
    private static String verifyProotBinary() throws Exception {
        String prootPath = TERMUX_FILES_DIR_PATH + "/" + APPS_BIN + "/proot";
        if (!new File(prootPath).isFile()) {
            return "proot binary not found at " + prootPath;
        }
        if (!new File(prootPath).canExecute()) {
            return "proot binary exists but is not executable";
        }
        String linker = "/system/bin/linker64";
        if (!new File(linker).isFile()) linker = "/system/bin/linker";
        Process proc = new ProcessBuilder(linker, prootPath, "--version")
            .redirectErrorStream(true)
            .start();
        boolean done = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            return "proot binary check timed out (5s)";
        }
        return proc.exitValue() == 0 ? null : "proot binary check failed (exit " + proc.exitValue() + ")";
    }

    /**
     * Verify that all deployed artifacts are valid and usable.
     * @return Error if any artifact is missing or broken, null if all good.
     */
    private static Error verifyDeployment() {
        String[][] artifacts = {
            {APPS_BIN + "/sandbox", "Shell sandbox script"},
            {APPS_BIN + "/proot", "Proot binary"},
            {APPS_BIN + "/init", "Container init script"},
            {APPS_BIN + "/setup", "Container setup script"},
            {ROOTFS_TARBALL, "Rootfs tarball"},
            {APPS_COM_TEXMUX + "/libexec/termux-api-broadcast", "Termux:API broadcast binary"},
        };
        for (String[] artifact : artifacts) {
            File f = new File(TERMUX_FILES_DIR_PATH + "/" + artifact[0]);
            if (!f.isFile()) {
                String msg = "Deployment artifact missing: " + artifact[0] + " (" + artifact[1] + ")";
                Logger.logError(LOG_TAG, msg);
                return ERRNO_DEPLOYMENT_FAILED.getError(msg);
            }
            if (artifact[0].endsWith("/proot") || artifact[0].endsWith("/sandbox")
                || artifact[0].endsWith("/init") || artifact[0].endsWith("/setup")) {
                if (!f.canExecute()) {
                    String msg = "Deployment artifact not executable: " + artifact[0] + " (" + artifact[1] + ")";
                    Logger.logError(LOG_TAG, msg);
                    return ERRNO_DEPLOYMENT_FAILED.getError(msg);
                }
            }
        }

        // Verify proot binary actually runs
        try {
            String prootError = verifyProotBinary();
            if (prootError != null) {
                Logger.logError(LOG_TAG, "Proot binary verification failed during deployment check: " + prootError);
                return ERRNO_PROOT_BINARY_FAILED.getError(prootError);
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Exception during proot binary verification: " + e.getMessage());
            return ERRNO_PROOT_BINARY_FAILED.getError(e, e.getMessage());
        }

        Logger.logInfo(LOG_TAG, "All deployment artifacts verified successfully");
        return null;
    }

    // ─── Extract rootfs using proot (streaming) ─────────────────────────────────────────
    // Runs proot with --link2symlink and tar -xvf to extract rootfs.
    // Each extracted file path is streamed to the log callback for live display.
    // Includes timeout, disk space check, and post-extraction verification.
    private static void extractRootfs(Activity activity,
                                      java.util.function.Consumer<String> log) throws Exception {
        File rootfsDir = new File(TERMUX_FILES_DIR_PATH, "ROOTFS");
        File marker = new File(rootfsDir, ".extracted");

        if (marker.isFile()) {
            // Double-check that the rootfs actually looks valid
            if (verifyRootfs(rootfsDir)) {
                Logger.logInfo(LOG_TAG, "Rootfs already extracted, skipping");
                log.accept("  (already extracted, skipping)");
                return;
            } else {
                // Marker exists but rootfs is corrupt — delete marker and re-extract
                Logger.logWarn(LOG_TAG, "Rootfs marker found but rootfs is corrupt, re-extracting");
                log.accept("  (marker found but rootfs corrupt, re-extracting...)");
                marker.delete();
            }
        }

        String appsBin = TERMUX_FILES_DIR_PATH + "/" + APPS_BIN;
        String tmpDir = TERMUX_FILES_DIR_PATH + "/" + TMP_DIR;
        String tarball = TERMUX_FILES_DIR_PATH + "/" + ROOTFS_TARBALL;
        String prootBin = appsBin + "/proot";
        String l2sDir = rootfsDir + "/.l2s";
        String prootTmp = tmpDir + "/.extract";
        String debugLog = TERMUX_FILES_DIR_PATH + "/extract_debug.log";

        // Pre-flight disk space check: rootfs is ~1-2GB, tarball ~300-500MB
        // Require at least 2GB free for extraction wiggle room
        String diskSpaceError = checkDiskSpace(2048);
        if (diskSpaceError != null) {
            Logger.logError(LOG_TAG, diskSpaceError);
            log.accept("  ERROR: " + diskSpaceError);
            throw new Exception(ERRNO_DISK_SPACE.getError(diskSpaceError).getMinimalErrorString());
        }

        // Verify proot binary works before attempting extraction
        String prootVerifyError = verifyProotBinary();
        if (prootVerifyError != null) {
            Logger.logError(LOG_TAG, prootVerifyError);
            log.accept("  ERROR: " + prootVerifyError);
            throw new Exception(ERRNO_PROOT_BINARY_FAILED.getError(prootVerifyError).getMinimalErrorString());
        }

        // Verify the tarball exists
        if (!new File(tarball).isFile()) {
            String msg = "Rootfs tarball not found at " + tarball;
            Logger.logError(LOG_TAG, msg);
            log.accept("  ERROR: " + msg);
            throw new Exception(ERRNO_ASSETS_MISSING.getError(msg).getMinimalErrorString());
        }

        rootfsDir.mkdirs();
        new File(l2sDir).mkdirs();
        new File(prootTmp).mkdirs();

        String prootLoader = activity.getApplicationInfo().nativeLibraryDir + "/libproot-loader.so";
        if (!new File(prootLoader).isFile()) {
            prootLoader = null;
        }

        Logger.logInfo(LOG_TAG, "Extracting rootfs to " + rootfsDir);
        log.accept("  Extracting rootfs (this may take a while)...");

        String shScript = "LINKER=/system/bin/linker64; " +
            "[ -f \"$LINKER\" ] || LINKER=/system/bin/linker; " +
            "export PROOT_TMP_DIR='" + prootTmp + "'; " +
            "export PROOT_L2S_DIR='" + l2sDir + "'; " +
            "\"$LINKER\" \"" + prootBin + "\" --link2symlink /system/bin/tar " +
            "-xvf \"" + tarball + "\" -C \"" + rootfsDir + "\"";

        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", shScript);
        pb.redirectErrorStream(true);
        pb.environment().put("PROOT_TMP_DIR", prootTmp);
        pb.environment().put("PROOT_L2S_DIR", l2sDir);
        if (prootLoader != null) {
            pb.environment().put("PROOT_LOADER", prootLoader);
        }

        Process process = pb.start();

        // Stream output line by line to both log and output buffer.
        // Reader thread prevents pipe buffer deadlock.
        final StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append('\n');
                    log.accept("  " + line);
                }
            } catch (java.io.IOException e) {
                output.append("\n[reader: ").append(e.getMessage()).append("]");
            }
        });
        reader.start();

        // Timeout: 5 minutes max for extraction
        boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            reader.join(2000);
            String msg = "Rootfs extraction timed out after 5 minutes";
            Logger.logError(LOG_TAG, msg);
            log.accept("  ERROR: " + msg);
            // Clean up for potential retry
            marker.delete();
            deleteDirectoryRecursive(new File(l2sDir));
            throw new Exception(ERRNO_EXTRACTION_TIMEOUT.getError(msg).getMinimalErrorString());
        }

        int exitCode = process.exitValue();
        reader.join(5000);

        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(debugLog),
                output.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        if (exitCode != 0) {
            String msg = "Rootfs extraction failed (exit " + exitCode + ").\nOutput:\n" + output;
            Logger.logError(LOG_TAG, msg);
            log.accept("  ERROR: Extraction failed (exit " + exitCode + ")");
            // Clean up for clean retry
            marker.delete();
            deleteDirectoryRecursive(new File(l2sDir));
            throw new Exception(ERRNO_EXTRACTION_FAILED.getError(msg).getMinimalErrorString());
        }

        if (!verifyRootfs(rootfsDir)) {
            String msg = "Rootfs extraction completed but verification failed "
                + "(expected /etc/passwd and /bin/sh)";
            Logger.logError(LOG_TAG, msg);
            log.accept("  ERROR: " + msg);
            // Clean up for clean retry
            marker.delete();
            deleteDirectoryRecursive(new File(l2sDir));
            throw new Exception(ERRNO_ROOTFS_CORRUPTED.getError(msg).getMinimalErrorString());
        }

        marker.createNewFile();
        log.accept("  ✓ extraction complete");
        Logger.logInfo(LOG_TAG, "Rootfs extracted successfully");
    }

    // ─── Incremental binary deployment ─────────────────────────────────────────────────
    // Deploy binaries that may have been added after the user's initial deployment,
    // without triggering a full re-deploy.
    private static void deployMissingBinaries(Activity activity) throws Exception {
        // Backward compat: deploy am-socket to old APPS_BIN location
        File amSocket = new File(new File(TERMUX_FILES_DIR_PATH, APPS_BIN), "am-socket");
        if (!amSocket.isFile()) {
            Logger.logInfo(LOG_TAG, "am-socket missing — deploying now");
            try (InputStream in = activity.getAssets().open("am-socket/termux-am-socket")) {
                try (FileOutputStream out = new FileOutputStream(amSocket)) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
                }
                amSocket.setExecutable(true, false);
            }
            Logger.logInfo(LOG_TAG, "Deployed am-socket to " + amSocket.getAbsolutePath());
        }
        // Deploy termux-api package if missing (termux-am-socket lives alongside api scripts now)
        File termuxAmSocketBinary = new File(TERMUX_FILES_DIR_PATH, APPS_COM_TEXMUX + "/bin/termux-am-socket");
        Logger.logInfo(LOG_TAG, "Deploying termux-am-socket...");
        try (InputStream in = activity.getAssets().open("am-socket/termux-am-socket")) {
            termuxAmSocketBinary.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(termuxAmSocketBinary)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            }
            termuxAmSocketBinary.setExecutable(true, false);
            // Backward compat symlink: am-socket → termux-am-socket
            new java.io.File(termuxAmSocketBinary.getParentFile(), "am-socket").delete();
            java.nio.file.Files.createSymbolicLink(
                new java.io.File(termuxAmSocketBinary.getParentFile(), "am-socket").toPath(),
                new java.io.File("termux-am-socket").toPath());
            Logger.logInfo(LOG_TAG, "Deployed termux-am-socket to " + termuxAmSocketBinary.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            Logger.logWarn(LOG_TAG, "termux-am-socket not found in assets, skipping");
        }

        // termux-am wrapper — deployed alongside termux-am-socket
        File termuxAm = new File(TERMUX_FILES_DIR_PATH, APPS_COM_TEXMUX + "/bin/termux-am");
        Logger.logInfo(LOG_TAG, "Deploying termux-am...");
        try (InputStream in = activity.getAssets().open("am-socket/termux-am")) {
            termuxAm.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(termuxAm)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            }
            termuxAm.setExecutable(true, false);
            Logger.logInfo(LOG_TAG, "Deployed termux-am to " + termuxAm.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            Logger.logWarn(LOG_TAG, "termux-am not found in assets, skipping");
        }

        File apiBroadcast = new File(TERMUX_FILES_DIR_PATH, APPS_COM_TEXMUX + "/libexec/termux-api-broadcast");
        if (!apiBroadcast.isFile()) {
            Logger.logInfo(LOG_TAG, "termux-api package missing — deploying now");
            deployTermuxApiPackage(activity);
        }
    }

    // ─── Deploy Termux:API package ──────────────────────────────────────────────────────────
    // Deploys the termux-api C binary + shell scripts to apps/com.termux/:
    //   termux-api-broadcast  → apps/com.termux/libexec/termux-api-broadcast
    //   termux-api            → apps/com.termux/bin/termux-api (build-time copy via CMake)
    //   termux-callback       → apps/com.termux/libexec/termux-callback
    //   termux-*.sh scripts   → apps/com.termux/bin/
    private static void deployTermuxApiPackage(Activity activity) throws Exception {
        File apiDir = new File(TERMUX_FILES_DIR_PATH, APPS_COM_TEXMUX);
        File libexecDir = new File(apiDir, "libexec");
        File binDir = new File(apiDir, "bin");
        libexecDir.mkdirs();
        binDir.mkdirs();

        // termux-api-broadcast — direct socket, no fork (stdin/notification support)
        deployTermuxApiBinary(activity, "termux-api/libexec/termux-api-broadcast", libexecDir, "termux-api-broadcast");

        // termux-callback script
        try (InputStream in = activity.getAssets().open("termux-api/libexec/termux-callback")) {
            File dst = new File(libexecDir, "termux-callback");
            try (FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            dst.setExecutable(true, false);
            Logger.logInfo(LOG_TAG, "Deployed termux-callback to " + dst.getAbsolutePath());
        } catch (java.io.FileNotFoundException e) {
            Logger.logWarn(LOG_TAG, "termux-callback not found in assets/termux-api/, skipping");
        }

        // termux-* shell scripts
        String[] scriptNames = {
            "termux-api", "termux-api-start", "termux-api-stop",
            "termux-audio-info",             "termux-battery-status", "termux-bluetooth", "termux-brightness",
            "termux-call-log", "termux-camera-info", "termux-camera-photo",
            "termux-clipboard-get", "termux-clipboard-set", "termux-contact-list",
            "termux-dialog", "termux-download", "termux-fingerprint",
            "termux-infrared-frequencies", "termux-infrared-transmit",
            "termux-job-scheduler", "termux-keystore", "termux-location",
            "termux-media-player", "termux-media-scan", "termux-microphone-record",
            "termux-nfc", "termux-notification", "termux-notification-channel",
            "termux-notification-list", "termux-notification-remove",
            "termux-open",
            "termux-saf-create", "termux-saf-dirs", "termux-saf-ls",
            "termux-saf-managedir", "termux-saf-mkdir", "termux-saf-read",
            "termux-saf-rm", "termux-saf-stat", "termux-saf-write",
            "termux-sensor",
            "termux-share", "termux-sms-inbox", "termux-sms-list",
            "termux-sms-send", "termux-speech-to-text", "termux-storage-get",
            "termux-telephony-call", "termux-telephony-cellinfo", "termux-telephony-deviceinfo",
            "termux-toast", "termux-torch", "termux-tts-engines", "termux-tts-speak",
            "termux-usb", "termux-vibrate", "termux-volume",             "termux-wallpaper",
            "termux-wifi",
            "termux-wifi-connectioninfo", "termux-wifi-enable", "termux-wifi-scaninfo"
        };
        for (String script : scriptNames) {
                try (InputStream in = activity.getAssets().open("termux-api/bin/" + script)) {
                File dst = new File(binDir, script);
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                dst.setExecutable(true, false);
            } catch (java.io.FileNotFoundException e) {
                // Script might not be present - that's ok for incremental builds
                Logger.logDebug(LOG_TAG, "termux-api script not found in assets: " + script);
            }
        }
        Logger.logInfo(LOG_TAG, "Deployed " + scriptNames.length + " termux-api scripts to " + binDir.getAbsolutePath());

        Logger.logInfo(LOG_TAG, "Termux:API package deployment complete");
    }

}
