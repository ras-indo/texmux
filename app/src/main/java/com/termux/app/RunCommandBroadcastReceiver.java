package com.termux.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BroadcastReceiver for {@code com.texmux.RUN_COMMAND} intent action.
 *
 * Termux supports both {@code am broadcast} and {@code am startservice} for sending
 * RUN_COMMAND intents. This receiver handles the broadcast path and forwards to
 * {@link RunCommandService} (which normally handles {@code am startservice}).
 *
 * Usage (via termux-am-socket / am-socket inside proot container):
 * <pre>
 * am broadcast -a com.texmux.RUN_COMMAND \
 *   --es com.texmux.RUN_COMMAND_PATH "ls" \
 *   --esa com.texmux.RUN_COMMAND_ARGUMENTS "-la" \
 *   --es com.texmux.RUN_COMMAND_WORKDIR "/sdcard" \
 *   --es com.texmux.RUN_COMMAND_SESSION_ACTION "0" \
 *   com.texmux
 * </pre>
 *
 * @see RunCommandService
 */
public class RunCommandBroadcastReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "RunCommandBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Reuse the broadcast Intent as a service Intent by pointing it at RunCommandService.
        // This preserves all extras (RUN_COMMAND_PATH, RUN_COMMAND_ARGUMENTS, etc.),
        // flags, and the action string so RunCommandService.onStartCommand() processes
        // it identically to an am startservice call.
        Intent serviceIntent = new Intent(intent);
        serviceIntent.setClass(context, RunCommandService.class);
        context.startService(serviceIntent);

        // Signal success to the am broadcast caller.
        setResultCode(-1); // Activity.RESULT_OK
    }
}
