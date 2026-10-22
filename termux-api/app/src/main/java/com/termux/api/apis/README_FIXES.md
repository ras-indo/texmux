Summary of fixes:

1. **termux-download (DownloadAPI.java)**
   - Fixed URL reading: now extracts URL from intent extras (client sends {"<url>":true} in args, not intent data)
   - Added URL validation (http://, https://, ftp:// schemes only)
   - Added proper JSON error responses for all failure cases
   - Returns JSON ack with downloadId on success
   - Validated download path before setting destination
   - Checked DownloadManager availability and enqueue success

2. **termux-speech-to-text (SpeechToTextAPI.java)**
   - Replaced shared queue with per-request queue (currentQueue) set in onHandleIntent
   - Added bounded wait timeout (30 seconds) using poll() instead of take()
   - Added dedicated error slots with platform error codes (ERROR:<code>:<message>)
   - JSON error responses instead of empty output on all failures
   - Per-utterance error handling with timeout (60 seconds)
   - JSON success response with text field on completion

3. **termux-tts-speak (TextToSpeechAPI.java)**
   - Checked speak() return codes (0=success, 1=queued, <0=error)
   - Immediate JSON error response for failed speak() calls
   - Bounded wait timeout (30 seconds) for utterance completion
   - JSON error responses for all failure paths
   - Proper cleanup on initialization failure (stopSelf() + JSON error)

All files have been verified to be syntactically correct and readable.