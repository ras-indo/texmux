package texmux.shared.termux;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import texmux.shared.logger.Logger;
import texmux.shared.termux.TermuxConstants.TERMUX_APP;

/**
 * Proot variant configuration for texmux.
 *
 * Unlike the original Termux which uses an APT bootstrap package extracted to
 * $PREFIX, texmux ships a full rootfs managed under ROOTFS/. This class tracks
 * the proot-enabled variant and provides minimal config that downstream code
 * needs to distinguish proot mode from the legacy Termux mode.
 */
public class TermuxProot {

    private static final String LOG_TAG = "TermuxProot";

    /** Path to the rootfs directory inside files/. */
    public static final String ROOTFS_DIR_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/ROOTFS";

    /** Always true for texmux — every build uses proot. */
    public static boolean isProotVariant() {
        return true;
    }

    // ---- Legacy config fields (kept for env var plumbing) ----

    /** The package manager name passed to the container as env var. Always "apt" for texmux. */
    public static String TERMUX_APP_PACKAGE_MANAGER = "apt";

    /** The package variant name passed to the container as env var. */
    public static String TERMUX_APP_PACKAGE_VARIANT = "proot";

    /**
     * Initialise the variant from a {@code packageVariantName} string (e.g. "apt-android-7").
     *
     * In the original Termux this would select between APT, TAPM and PACMAN variants
     * targeting different Android API levels. In texmux the rootfs is identical
     * regardless of the Android version, so this is a thin compatibility shim.
     */
    public static void setTermuxPackageManagerAndVariant(@Nullable String packageVariantName) {
        if (packageVariantName == null) return;

        TERMUX_APP_PACKAGE_VARIANT = packageVariantName;

        // Package manager is the substring before the first dash, e.g. "apt" from "apt-android-7"
        int index = packageVariantName.indexOf('-');
        TERMUX_APP_PACKAGE_MANAGER = (index == -1) ? packageVariantName : packageVariantName.substring(0, index);

        Logger.logVerbose(LOG_TAG, "Set TERMUX_APP_PACKAGE_VARIANT to \"" + TERMUX_APP_PACKAGE_VARIANT
            + "\", TERMUX_APP_PACKAGE_MANAGER to \"" + TERMUX_APP_PACKAGE_MANAGER + "\"");
    }

}
