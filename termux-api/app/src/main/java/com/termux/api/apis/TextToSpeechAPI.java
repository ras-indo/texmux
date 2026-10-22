package com.termux.api.apis;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.speech.tts.UtteranceProgressListener;
import android.util.JsonWriter;

import com.termux.api.util.ResultReturner;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TextToSpeechAPI {

    private static final String LOG_TAG = "TextToSpeechAPI";

    public static void onReceive(final Context context, Intent intent) {
        Logger.logDebug(LOG_TAG, "onReceive");

        Intent ttsIntent = new Intent(context, TextToSpeechService.class);
        Bundle extras = intent.getExtras();
        if (extras != null) ttsIntent.putExtras(extras);
        context.startService(ttsIntent);
    }

    public static class TextToSpeechService extends IntentService {
        TextToSpeech mTts;
        final CountDownLatch mTtsLatch = new CountDownLatch(1);

        private static final String LOG_TAG = "TextToSpeechService";

        public TextToSpeechService() {
            super(TextToSpeechService.class.getName());
        }

        @Override
        public void onCreate() {
            Logger.logDebug(LOG_TAG, "onCreate");

            super.onCreate();
        }

        @Override
        public void onDestroy() {
            Logger.logDebug(LOG_TAG, "onDestroy");

            if (mTts != null)
                mTts.shutdown();
            super.onDestroy();
        }

        @Override
        protected void onHandleIntent(final Intent intent) {
            Logger.logDebug(LOG_TAG, "onHandleIntent:\n" + IntentUtils.getIntentString(intent));

            final String speechLanguage = intent.getStringExtra("language");
            final String speechRegion = intent.getStringExtra("region");
            final String speechVariant = intent.getStringExtra("variant");
            final String speechEngine = intent.getStringExtra("engine");
            final float speechPitch = intent.getFloatExtra("pitch", 1.0f);

            // STREAM_MUSIC is the default audio stream for TTS, see:
            // http://stackoverflow.com/questions/6877272/what-is-the-default-audio-stream-of-tts/6979025#6979025
            int streamToUseInt = AudioManager.STREAM_MUSIC;
            String streamToUseString = intent.getStringExtra("stream");
            if (streamToUseString != null) {
                switch (streamToUseString) {
                    case "NOTIFICATION":
                        streamToUseInt = AudioManager.STREAM_NOTIFICATION;
                        break;
                    case "ALARM":
                        streamToUseInt = AudioManager.STREAM_ALARM;
                        break;
                    case "MUSIC":
                        streamToUseInt = AudioManager.STREAM_MUSIC;
                        break;
                    case "RING":
                        streamToUseInt = AudioManager.STREAM_RING;
                        break;
                    case "SYSTEM":
                        streamToUseInt = AudioManager.STREAM_SYSTEM;
                        break;
                    case "VOICE_CALL":
                        streamToUseInt = AudioManager.STREAM_VOICE_CALL;
                        break;
                }
            }
            final int streamToUse = streamToUseInt;

            mTts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    mTtsLatch.countDown();
                } else {
                    Logger.logError(LOG_TAG, "Failed tts initialization: status=" + status);
                    stopSelf();
                }
            }, speechEngine);

            ResultReturner.returnData(this, intent, new ResultReturner.WithInput() {
                @Override
                public void writeResult(PrintWriter out) {

                    try {
                        try {
                            if (!mTtsLatch.await(10, TimeUnit.SECONDS)) {
                                Logger.logError(LOG_TAG, "Timeout waiting for TTS initialization");
                                out.println("{\"success\":false,\"error\":\"TTS initialization timed out\"}");
                                return;
                            }
                        } catch (InterruptedException e) {
                            Logger.logError(LOG_TAG, "Interrupted awaiting TTS initialization");
                            return;
                        }

                        if ("LIST_AVAILABLE".equals(speechEngine)) {
                            try (JsonWriter writer = new JsonWriter(out)) {
                                writer.setIndent("  ");
                                String defaultEngineName = mTts.getDefaultEngine();
                                writer.beginArray();
                                for (EngineInfo info : mTts.getEngines()) {
                                    writer.beginObject();
                                    writer.name("name").value(info.name);
                                    writer.name("label").value(info.label);
                                    writer.name("default").value(defaultEngineName.equals(info.name));
                                    writer.endObject();
                                }
                                writer.endArray();
                            }
                            out.println();
                            return;
                        }

                        final AtomicInteger submittedUtterances = new AtomicInteger();
                        final AtomicInteger completedUtterances = new AtomicInteger();

                        mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override
                            public void onStart(String utteranceId) {
                                // Ignore.
                            }

                            @Override
                            public void onError(String utteranceId) {
                                Logger.logError(LOG_TAG, "UtteranceProgressListener.onError: " + utteranceId);
                                synchronized (completedUtterances) {
                                    completedUtterances.incrementAndGet();
                                    completedUtterances.notify();
                                }
                            }

                            @Override
                            public void onDone(String utteranceId) {
                                synchronized (completedUtterances) {
                                    completedUtterances.incrementAndGet();
                                    completedUtterances.notify();
                                }
                            }
                        });

                        if (speechLanguage != null) {
                            int setLanguageResult = mTts.setLanguage(getLocale(speechLanguage, speechRegion, speechVariant));
                            if (setLanguageResult != TextToSpeech.LANG_AVAILABLE) {
                                Logger.logError(LOG_TAG, "tts.setLanguage('" + speechLanguage + "') returned " + setLanguageResult);
                            }
                        }

                        mTts.setPitch(speechPitch);
                        mTts.setSpeechRate(intent.getFloatExtra("rate", 1.0f));

                        String utteranceId = "utterance_id";
                        Bundle params = new Bundle();
                        params.putInt(Engine.KEY_PARAM_STREAM, streamToUse);
                        params.putString(Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

                        int submittedCount = 0;
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.isEmpty()) {
                                    submittedCount++;
                                    // Speak returns TextToSpeech.SUCCESS (0),
                                    // TextToSpeech.QUEUED (1), or an error code < 0.
                                    // Check the return code — a value < 0 means the engine
                                    // rejected this utterance (e.g. engine not ready). Report
                                    // as JSON immediately instead of hanging on the wait.
                                    int speakResult = mTts.speak(line, TextToSpeech.QUEUE_ADD, params, utteranceId);
                                    if (speakResult < 0) {
                                        // Engine returned an error — report as JSON
                                        out.println("{\"success\":false,\"error\":\"TTS speak failed (code=" + speakResult + ")\"}");
                                        return;
                                    }
                                }
                            }
                        }

                        if (submittedCount > 0) {
                            // Wait with a bounded timeout for all onDone/onError callbacks.
                            // Each speak() call increments completedUtterances via the listener;
                            // we poll it with a timeout so a failed engine can never hang.
                            long deadlineMs = System.currentTimeMillis() + 30000;
                            boolean timedOut = false;
                            synchronized (completedUtterances) {
                                while (completedUtterances.get() < submittedCount) {
                                    try {
                                        long remainingMs = deadlineMs - System.currentTimeMillis();
                                        if (remainingMs <= 0) {
                                            timedOut = true;
                                            break;
                                        }
                                        completedUtterances.wait(remainingMs);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            }

                            if (completedUtterances.get() < submittedCount) {
                                // Speech did not complete in bounded time or engine failed.
                                out.println("{\"success\":false,\"error\":\"TTS speech did not complete in time\"}");
                                return;
                            }
                        }

                        // Success: emit JSON ack.
                        JSONObject ack = new JSONObject();
                        try {
                            ack.put("success", true);
                            ack.put("message", "Speech completed: " + submittedCount);
                            out.println(ack.toString());
                        } catch (Exception e) {
                            out.println("{\"success\":true}");
                        }
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "TTS error", e);
                        out.println("{\"success\":false,\"error\":\"TTS error: " + e.getMessage() + "\"}");
                    }
                }
            });
        }
    }

    private static Locale getLocale(String language, String region, String variant) {
        Locale result;
        if (region != null) {
            if (variant != null) {
                result = new Locale(language, region, variant);
            } else {
                result = new Locale(language, region);
            }
        } else {
            result = new Locale(language);
        }
        return result;
    }
}
