package com.teleprompterpro.app.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.teleprompterpro.app.settings.VoiceLanguage
import com.teleprompterpro.app.util.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI shows about voice sync right now. */
sealed interface VoiceSyncStatus {
    data object Off : VoiceSyncStatus
    data object Starting : VoiceSyncStatus
    data class Listening(val lastHeard: String, val onDevice: Boolean) : VoiceSyncStatus
    /** Voice sync cannot run; the reader must fall back to timed scrolling. */
    data class Unavailable(val reason: String) : VoiceSyncStatus
}

/**
 * Real word-level voice sync built on Android's [SpeechRecognizer].
 *
 * Partial results are streamed continuously; each hypothesis is fed to a
 * [WordMatcher] and the reader advances only when spoken words match script
 * words. Ambient noise, music, or speech in a different language produce no
 * matches — and therefore no scrolling.
 *
 * Honest limits, surfaced to the user rather than hidden:
 *  - On API 31+ we prefer [SpeechRecognizer.createOnDeviceSpeechRecognizer]
 *    when the device says on-device recognition is available; otherwise the
 *    default recognizer is used, which on many phones is Google's service and
 *    may need its offline language pack installed for offline use.
 *  - Urdu (ur-PK) works only if the recognizer on the phone has that model.
 *    If the recognizer rejects the language we report Unavailable with the
 *    reason instead of silently listening in English.
 *  - While a video is recording, some devices refuse a second microphone
 *    client or hand the recognizer silence. We detect that (audio/busy errors,
 *    or repeated empty cycles) and emit Unavailable so the reader falls back to
 *    timed scrolling with a visible notice.
 */
class SpeechSyncEngine(private val context: Context) {

    private val _status = MutableStateFlow<VoiceSyncStatus>(VoiceSyncStatus.Off)
    val status: StateFlow<VoiceSyncStatus> = _status

    private val _hypotheses = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Partial + final recognizer text, newest last. */
    val hypotheses: SharedFlow<String> = _hypotheses

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var language: VoiceLanguage = VoiceLanguage.ENGLISH
    private var running = false
    private var onDevice = false
    private var consecutiveHardErrors = 0
    private var consecutiveEmptyCycles = 0
    private var sessionStartedAt = 0L

    /** Whether *any* recognizer exists on this device. */
    fun isAvailable(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun start(language: VoiceLanguage) {
        main.post {
            if (running) return@post
            this.language = language
            consecutiveHardErrors = 0
            consecutiveEmptyCycles = 0
            if (!isAvailable()) {
                _status.value = VoiceSyncStatus.Unavailable("No speech recognizer is installed on this phone.")
                return@post
            }
            running = true
            _status.value = VoiceSyncStatus.Starting
            createRecognizer()
            listen()
        }
    }

    fun stop() {
        main.post {
            running = false
            destroyRecognizer()
            _status.value = VoiceSyncStatus.Off
        }
    }

    private fun createRecognizer() {
        destroyRecognizer()
        val r = try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                onDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                onDevice = false
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            Logger.w("Voice", "Recognizer create failed", e)
            running = false
            _status.value = VoiceSyncStatus.Unavailable("Speech recognizer could not be started.")
            return
        }
        r.setRecognitionListener(listener)
        recognizer = r
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        runCatching { r.cancel() }
        runCatching { r.destroy() }
    }

    private fun listen() {
        if (!running) return
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.tag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Keep sessions long; we restart on our own when the service ends one.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 20_000L)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
            }
        }
        sessionStartedAt = System.currentTimeMillis()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Logger.w("Voice", "startListening failed", e)
            fail("Speech recognizer refused to start.")
        }
    }

    private fun restartSoon(delayMs: Long = 250L) {
        if (!running) return
        main.postDelayed({ if (running) listen() }, delayMs)
    }

    private fun fail(reason: String) {
        running = false
        destroyRecognizer()
        _status.value = VoiceSyncStatus.Unavailable(reason)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveHardErrors = 0
            if (_status.value !is VoiceSyncStatus.Listening) {
                _status.value = VoiceSyncStatus.Listening("", onDevice)
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit // deliberately unused: we never scroll on volume
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
            consecutiveEmptyCycles = 0
            _status.value = VoiceSyncStatus.Listening(text, onDevice)
            _hypotheses.tryEmit(text)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                consecutiveEmptyCycles = 0
                _status.value = VoiceSyncStatus.Listening(text, onDevice)
                _hypotheses.tryEmit(text)
            } else {
                consecutiveEmptyCycles++
            }
            restartSoon()
        }

        override fun onError(error: Int) {
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal between sentences. But if a session died almost
                    // instantly several times in a row, the mic is likely being
                    // fed silence (e.g. the recorder owns it) — fall back.
                    val lived = System.currentTimeMillis() - sessionStartedAt
                    consecutiveEmptyCycles++
                    if (lived < 1500 && consecutiveEmptyCycles >= 6) {
                        fail("The microphone is busy with the video recording, so speech can't be heard.")
                    } else {
                        restartSoon(if (lived < 1500) 800L else 200L)
                    }
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    consecutiveHardErrors++
                    if (consecutiveHardErrors >= 3) fail("The speech recognizer is busy (used by another app).")
                    else { createRecognizer(); restartSoon(600L) }
                }
                SpeechRecognizer.ERROR_AUDIO -> {
                    consecutiveHardErrors++
                    if (consecutiveHardErrors >= 2) fail("The microphone is in use by the video recorder on this device.")
                    else restartSoon(800L)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail("Microphone permission is required for voice sync.")
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    fail("${language.label} is not available in this phone's speech recognizer.")
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                    fail("This phone's speech recognizer needs an offline language pack for ${language.label}.")
                SpeechRecognizer.ERROR_CLIENT -> {
                    consecutiveHardErrors++
                    if (consecutiveHardErrors >= 3) fail("Speech recognizer stopped responding.")
                    else { createRecognizer(); restartSoon(500L) }
                }
                else -> {
                    consecutiveHardErrors++
                    if (consecutiveHardErrors >= 3) fail("Speech recognizer error ($error).")
                    else restartSoon(500L)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
