package texmux.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import texmux.api.ApiLogConfig;
import texmux.api.util.ResultReturner;
import texmux.shared.data.IntentUtils;
import texmux.shared.logger.Logger;
import texmux.shared.termux.TermuxConstants;
import texmux.shared.termux.plugins.TermuxPluginUtils;

import org.json.JSONObject;

public class TermuxApiReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "TermuxApiReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        ApiLogConfig.setLogConfig(context, false);
        Logger.logDebug(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        try {
            doWork(context, intent);
        } catch (Throwable t) {
            String message = "Error in " + LOG_TAG;
            // Make sure never to throw exception from BroadCastReceiver to avoid "process is bad"
            // behaviour from the Android system.
            Logger.logStackTraceWithMessage(LOG_TAG, message, t);

            TermuxPluginUtils.sendPluginCommandErrorNotification(context, LOG_TAG,
                    TermuxConstants.TERMUX_API_APP_NAME + " Error", message, t);

            // Answer the client even when the handler failed. Without this the socket is
            // closed with zero bytes written, and a command that throws looks exactly like a
            // command that hangs: no output, no error, no exit status anyone can act on.
            try {
                final String reason = t.getMessage() == null
                        ? t.toString() : t.getClass().getSimpleName() + ": " + t.getMessage();
                ResultReturner.returnData(this, intent,
                        out -> out.println(new JSONObject().put("API_ERROR", reason).toString()));
            } catch (Throwable ignored) {
                ResultReturner.noteDone(this, intent);
            }
        }
    }

    private void doWork(Context context, Intent intent) {
        DirectDispatcher.dispatch(this, context, intent);
    }

}
