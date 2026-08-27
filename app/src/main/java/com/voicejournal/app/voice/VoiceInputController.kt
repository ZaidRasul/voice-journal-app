package com.voicejournal.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

enum class VoiceSessionMode {
    SINGLE_NOTE,
    RANT
}

/**
 * Thin lifecycle-aware wrapper around the phone's speech service. It persists
 * only final results; partial phrases are display-only so they cannot duplicate
 * text in a note.
 */
class VoiceInputController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit
) : RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var mode: VoiceSessionMode? = null
    private var segmentFinalized = false
    private var restartAttempts = 0

    val isRunning: Boolean
        get() = mode != null

    fun startSingle() {
        onMain { start(VoiceSessionMode.SINGLE_NOTE) }
    }

    fun startRant() {
        onMain { start(VoiceSessionMode.RANT) }
    }

    fun stop() {
        onMain {
            mode = null
            handler.removeCallbacksAndMessages(null)
            recognizer?.cancel()
            releaseRecognizer()
            onStatus("Voice input stopped")
        }
    }

    fun destroy() {
        onMain {
            mode = null
            handler.removeCallbacksAndMessages(null)
            releaseRecognizer()
        }
    }

    private fun start(newMode: VoiceSessionMode) {
        mode = null
        handler.removeCallbacksAndMessages(null)
        releaseRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFailure("No speech recognizer is installed on this phone.")
            return
        }

        val created = runCatching {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrElse {
            onFailure("The phone could not start speech recognition.")
            return
        }

        recognizer = created
        recognizer?.setRecognitionListener(this)
        mode = newMode
        restartAttempts = 0
        startNextSegment()
    }

    private fun startNextSegment() {
        if (mode == null || recognizer == null) {
            return
        }

        segmentFinalized = false
        onPartial("")
        onStatus(
            if (mode == VoiceSessionMode.RANT) {
                "Rant mode is listening…"
            } else {
                "Listening…"
            }
        )

        runCatching {
            recognizer?.startListening(recognitionIntent())
        }.onFailure {
            finishWithError("Speech recognition is busy. Please try again.")
        }
    }

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    override fun onReadyForSpeech(params: Bundle?) {
        onStatus(if (mode == VoiceSessionMode.RANT) "Rant mode is listening…" else "Listening…")
    }

    override fun onBeginningOfSpeech() {
        onStatus("Hearing you…")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        onStatus("Turning speech into text…")
    }

    override fun onError(error: Int) {
        if (mode == null) {
            return
        }

        val message = errorMessage(error)
        val mayRestart = mode == VoiceSessionMode.RANT &&
            (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
            restartAttempts < MAX_RESTART_ATTEMPTS

        if (mayRestart) {
            restartAttempts += 1
            onStatus("No words heard. Listening again…")
            scheduleRestart(RESTART_DELAY_MS)
        } else {
            finishWithError(message)
        }
    }

    override fun onResults(results: Bundle?) {
        if (mode == null || segmentFinalized) {
            return
        }
        segmentFinalized = true

        val transcript = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (transcript.isNotBlank()) {
            restartAttempts = 0
            onPartial("")
            onFinal(transcript)
        }

        if (mode == VoiceSessionMode.RANT) {
            onStatus("Saved that thought. Listening again…")
            scheduleRestart(RESTART_DELAY_MS)
        } else {
            mode = null
            releaseRecognizer()
            onStatus("Voice input complete")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (mode == null) {
            return
        }
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        onPartial(partial)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun scheduleRestart(delayMillis: Long) {
        handler.postDelayed(
            {
                if (mode == VoiceSessionMode.RANT) {
                    startNextSegment()
                }
            },
            delayMillis
        )
    }

    private fun finishWithError(message: String) {
        val runningMode = mode
        mode = null
        releaseRecognizer()
        onFailure(message)
        if (runningMode != null) {
            onStatus("Voice input stopped")
        }
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "The microphone had an audio error."
        SpeechRecognizer.ERROR_CLIENT -> "Speech recognition was cancelled."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required for voice input."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The phone's speech service needs a connection or an offline language pack."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Another speech session is already active."
        SpeechRecognizer.ERROR_SERVER -> "The phone's speech service had a server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "This phone's speech service does not support the current language."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The current language model is not available on this phone."
        else -> "Speech recognition stopped unexpectedly."
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post(action)
        }
    }

    private companion object {
        const val RESTART_DELAY_MS = 450L
        const val MAX_RESTART_ATTEMPTS = 3
    }
}
