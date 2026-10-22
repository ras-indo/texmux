package com.termux.api.apis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.termux.api.activities.TermuxApiPermissionActivity;
import com.termux.api.util.ResultReturner;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public class SpeechToTextAPI {

    private static final String LOG_TAG = "SpeechToTextAPI";

    public static class SpeechToTextService extends IntentService {

        public SpeechToTextService() {
            this(SpeechToTextService.class.getSimpleName());
        }

        public SpeechToTextService(String name) {
            super(name);
        }

        protected SpeechRecognizer mSpeechRecognizer;
        // Per-request queue, set by onHandleIntent before starting recognition.
        // The RecognitionListener callbacks (which run on a different thread)
        // push results/errors into this queue; onHandleIntent polls it with a
        // bounded timeout so a stalled engine can never hang the command.
        private volatile LinkedBlockingQueue<String> currentQueue;
        private static final long WAIT_TIMEOUT_MS = 30000;

        private static final String LOG_TAG = "SpeechToTextService";

        @Override
        public void onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate");

            super.onCreate();
            final Context context = this;

            mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

            mSpeechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onRmsChanged(float rmsdB) {
                    // Do nothing.
                }

                @Override
                public void onResults(Bundle results) {
                    List<String> recognitions = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    Logger.logError(LOG_TAG, "RecognitionListener#onResults(" + recognitions + ")");
                    LinkedBlockingQueue<String> q = currentQueue;
                    if (q != null && recognitions != null) q.addAll(recognitions);
                }

                @Override
                public void onReadyForSpeech(Bundle params) {
                    // Do nothing.
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    // Do nothing.
                    List<String> strings = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    Logger.logError(LOG_TAG, "RecognitionListener#onPartialResults(" + strings + ")");
                    LinkedBlockingQueue<String> q = currentQueue;
                    if (q != null && strings != null) q.addAll(strings);
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                    // Do nothing.
                }

                @Override
                public void onError(int error) {
                    String description;
                    switch (error) {
                        case SpeechRecognizer.ERROR_CLIENT:
                            description = "ERROR_CLIENT";
                            break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            description = "ERROR_SPEECH_TIMEOUT";
                            break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                            description = "ERROR_RECOGNIZER_BUSY";
                            break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            description = "ERROR_INSUFFICIENT_PERMISSIONS";
                            break;
                        default:
                            description = Integer.toString(error);
                    }
                    Logger.logError(LOG_TAG, "RecognitionListener#onError(" + description + ")");
                    // Push a dedicated error slot carrying platform error code + message
                    // so onHandleIntent can emit JSON instead of empty output.
                    LinkedBlockingQueue<String> q = currentQueue;
                    if (q != null) q.add("ERROR:" + error + ":" + description);
                }

                @Override
                public void onEndOfSpeech() {
                    Logger.logError(LOG_TAG, "RecognitionListener#onEndOfSpeech()");
                    // Push dedicated END element so handler knows recognition ended
                    LinkedBlockingQueue<String> q = currentQueue;
                    if (q != null) q.add("END:");
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                    // Do nothing.
                }

                @Override
                public void onBeginningOfSpeech() {
                    // Do nothing.
                }
            });

            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> installedList = pm.queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
            boolean speechRecognitionInstalled = !installedList.isEmpty();

            if (!speechRecognitionInstalled) {
                // confirm button / Install Button click handler
                new AlertDialog.Builder(context).setMessage("For recognition it's necessary to install \\"Google Voice Search\\"")
                        .setTitle("Install Voice Search from Google Play?").setPositiveButton("Install", (dialog, which) -> {
                            Intent installIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.voicesearch"));
                            // setting flags to avoid going in application history (Activity call stack)
                            installIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                            context.startActivity(installIntent);
                        }).setNegativeButton("Cancel", null) // cancel button
                        .create().show();
            }

            Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Enter shell command");
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 10);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            mSpeechRecognizer.startListening(recognizerIntent);
        }

        @Override
        public void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");

            super.onDestroy();
            mSpeechRecognizer.destroy();
        }

        @Override
        protected void onHandleIntent(final Intent intent) {
            Logger.logDebug(LOG_TAG, "onHandleIntent:\n" + IntentUtils.getIntentString(intent));

            currentQueue = new LinkedBlockingQueue<>();
            ResultReturner.returnData(this, intent, new ResultReturner.WithInput() {
                @Override
                public void writeResult(PrintWriter out) throws Exception {
                    StringBuilder text = new StringBuilder();
                    boolean errorSeen = false;
                    String errorMsg = null;
                    int errorCode = -1;

                    long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
                    while (true) {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0) {
                            // Bounded wait elapsed without result or error
                            break;
                        }
                        String s = currentQueue.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (s == null) {
                            // Timeout: nothing arrived in remaining time
                            break;
                        }
                        if (s.startsWith("ERROR:")) {
                            // Dedicated error slot: platform error code + message
                            String[] parts = s.split(":", 3);
                            errorCode = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
                            errorMsg = parts.length > 2 ? parts[2] : "";
                            errorSeen = true;
                            break;
                        }
                        if (s.equals("END:")) {
                            // Recognition ended normally
                            break;
                        }
                        // Regular recognition result text
                        text.append(s).append("\n");
                    }

                    if (errorSeen) {
                        JSONObject err = new JSONObject();
                        try {
                            err.put("success", false);
                            err.put("error_code", errorCode);
                            err.put("error", errorMsg);
                            out.println(err.toString());
                        } catch (Exception e) {
                            out.println("{\"success\":false,\"error\":\"speech recognition failed\"}");
                        }
                        return;
                    }

                    String resultText = text.length() > 0 ? text.toString().trim() : "";
                    JSONObject resp = new JSONObject();
                    try {
                        resp.put("success", true);
                        resp.put("text", resultText);
                        out.println(resp.toString());
                    } catch (Exception e) {
                        out.println("{\"success\":true,\"text\":\"\"}");
                    }
                }
            });
        }
    }

    public static void onReceive(final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        if (!TermuxApiPermissionActivity.checkAndRequestPermissions(context, intent,
                Manifest.permission.RECORD_AUDIO)) return;

        Intent serviceIntent = new Intent(context, SpeechToTextService.class);
        Bundle extras = intent.getExtras();
        if (extras != null) serviceIntent.putExtras(extras);
        context.startService(serviceIntent);
    }

    public static void runFromActivity(final Activity context) {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> installedList = pm.queryIntentActivities(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
        boolean speechRecognitionInstalled = !installedList.isEmpty();

        if (speechRecognitionInstalled) {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Select an application"); // user hint
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1); // quantity of results we want to receive
            // context.startActivityForResult(intent, VOICE_RECOGNITION_REQUEST_CODE);
        } else {
            // confirm button / Install Button click handler
            new AlertDialog.Builder(context).setMessage("For recognition it's necessary to install \\"Google Voice Search\\"")
                    .setTitle("Install Voice Search from Google Play?").setPositiveButton("Install", (dialog, which) -> {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.voicesearch"));
                        // setting flags to avoid going in application history (Activity call stack)
                        intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
                        context.startActivity(intent);
                    }).setNegativeButton("Cancel", null) // cancel button
                    .create().show();
        }
    }
}
