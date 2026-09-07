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
    BRAIN_DUMP
}

/**
 * Thin lifecycle-aware wrapper around the phone's speech service. Confirmed
 * one-shot segments are accumulated across recognizer endpoint pauses. A partial
 * segment is included only when the user explicitly stops that one-shot session.
 */
class VoiceInputController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailure: (String) -> Unit
) : RecognitionListener {

    private val handler = Handler(Looper.getMainLooper())
    private val singleSegments = VoiceSegmentAccumulator()
    private var recognizer: SpeechRecognizer? = null
    private var mode: VoiceSessionMode? = null
    private var brainDumpPartial = ""
    private var segmentFinalized = false
    private var singleCompletionPending = false
    private var restartAttempts = 0
    private var usingOnDeviceRecognizer = false
    private var standardFallbackAttempted = false

    private val finishSingleAfterPause = Runnable {
        singleCompletionPending = false
        if (mode == VoiceSessionMode.SINGLE_NOTE && singleSegments.hasFinalText) {
            completeSingle(includePartial = false)
        }
    }

    val isRunning: Boolean
        get() = mode != null

    fun startSingle() {
        onMain { start(VoiceSessionMode.SINGLE_NOTE) }
    }

    fun startBrainDump() {
        onMain { start(VoiceSessionMode.BRAIN_DUMP) }
    }

    fun stop() {
        onMain {
            if (mode == VoiceSessionMode.SINGLE_NOTE) {
                completeSingle(includePartial = true, stoppedByUser = true)
                return@onMain
            }

            val pendingBrainDumpText = brainDumpPartial.trim()
            mode = null
            handler.removeCallbacksAndMessages(null)
            recognizer?.cancel()
            releaseRecognizer()
            brainDumpPartial = ""
            onPartial("")
            if (pendingBrainDumpText.isNotBlank()) {
                onFinal(pendingBrainDumpText)
            }
            onStatus("Voice input stopped")
        }
    }

    fun destroy() {
        onMain {
            mode = null
            handler.removeCallbacksAndMessages(null)
            singleCompletionPending = false
            singleSegments.reset()
            brainDumpPartial = ""
            releaseRecognizer()
        }
    }

    private fun start(newMode: VoiceSessionMode) {
        mode = null
        handler.removeCallbacksAndMessages(null)
        singleCompletionPending = false
        singleSegments.reset()
        brainDumpPartial = ""
        releaseRecognizer()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onFailure("No speech recognizer is installed on this phone.")
            return
        }

        standardFallbackAttempted = false
        val prefersOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val created = createRecognizer(prefersOnDevice) ?: run {
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
        onPartial(if (mode == VoiceSessionMode.SINGLE_NOTE) singleSegments.finalText else "")
        onStatus(listeningStatus())

        runCatching {
            recognizer?.startListening(recognitionIntent())
        }.onFailure {
            retryAfterTransientFailure("Speech recognition is busy. Retrying…")
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                COMPLETE_SILENCE_MS
            )
        }

    override fun onReadyForSpeech(params: Bundle?) {
        onStatus(listeningStatus())
    }

    override fun onBeginningOfSpeech() {
        if (mode == VoiceSessionMode.SINGLE_NOTE) {
            cancelSingleCompletion()
        }
        onStatus("Hearing you…")
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        onStatus("Turning speech into text…")
    }

    override fun onError(error: Int) {
        if (mode == null || segmentFinalized) {
            return
        }

        val message = errorMessage(error)
        val languageModelUnavailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        val canUseStandardFallback = usingOnDeviceRecognizer &&
            !standardFallbackAttempted &&
            languageModelUnavailable
        if (canUseStandardFallback) {
            standardFallbackAttempted = true
            releaseRecognizer()
            val fallback = createRecognizer(preferOnDevice = false)
            if (fallback == null) {
                finishWithError(message)
                return
            }
            recognizer = fallback
            recognizer?.setRecognitionListener(this)
            onStatus("Using the phone's speech service…")
            handler.post(::startNextSegment)
            return
        }

        val isSilence = error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (mode == VoiceSessionMode.BRAIN_DUMP && isSilence) {
            restartAttempts = 0
            onStatus("No words heard. Listening again…")
            scheduleRestart(RESTART_DELAY_MS)
        } else if (
            mode == VoiceSessionMode.SINGLE_NOTE &&
            isSilence &&
            singleSegments.hasFinalText
        ) {
            singleSegments.updatePartial("")
            onPartial(singleSegments.finalText)
            onStatus(singlePauseStatus())
            if (!singleCompletionPending) {
                scheduleSingleCompletion()
            }
            scheduleRestart(RESTART_DELAY_MS)
        } else if (isTransientError(error)) {
            retryAfterTransientFailure("Speech recognition paused. Retrying…")
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

        val receivedNewText = transcript.isNotBlank()
        if (receivedNewText) {
            restartAttempts = 0
            if (mode == VoiceSessionMode.SINGLE_NOTE) {
                singleSegments.addFinal(transcript)
                onPartial(singleSegments.finalText)
            } else {
                brainDumpPartial = ""
                onPartial("")
                onFinal(transcript)
            }
        }

        if (mode == VoiceSessionMode.BRAIN_DUMP) {
            onStatus("Saved that thought. Listening again…")
            scheduleRestart(RESTART_DELAY_MS)
        } else if (singleSegments.hasFinalText) {
            onStatus(singlePauseStatus())
            if (receivedNewText || !singleCompletionPending) {
                scheduleSingleCompletion()
            }
            scheduleRestart(RESTART_DELAY_MS)
        } else {
            mode = null
            releaseRecognizer()
            onFailure("No speech was recognized.")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        if (mode == null || segmentFinalized) {
            return
        }
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (mode == VoiceSessionMode.SINGLE_NOTE) {
            singleSegments.updatePartial(partial)
            if (partial.isNotBlank()) {
                cancelSingleCompletion()
            }
            onPartial(singleSegments.previewText)
        } else {
            brainDumpPartial = partial
            onPartial(partial)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun scheduleRestart(delayMillis: Long) {
        val scheduledMode = mode ?: return
        handler.postDelayed(
            {
                if (mode == scheduledMode) {
                    startNextSegment()
                }
            },
            delayMillis
        )
    }

    private fun scheduleSingleCompletion() {
        cancelSingleCompletion()
        singleCompletionPending = true
        handler.postDelayed(finishSingleAfterPause, SINGLE_COMPLETION_GRACE_MS)
    }

    private fun cancelSingleCompletion() {
        if (singleCompletionPending) {
            handler.removeCallbacks(finishSingleAfterPause)
            singleCompletionPending = false
        }
    }

    private fun retryAfterTransientFailure(status: String) {
        val activeMode = mode ?: return
        if (activeMode == VoiceSessionMode.BRAIN_DUMP && brainDumpPartial.isNotBlank()) {
            val recoveredText = brainDumpPartial
            brainDumpPartial = ""
            onPartial("")
            onFinal(recoveredText)
        }

        restartAttempts += 1
        if (
            activeMode == VoiceSessionMode.SINGLE_NOTE &&
            restartAttempts > MAX_SINGLE_TRANSIENT_RETRIES
        ) {
            finishWithError("Speech recognition keeps stopping. Please try again.")
            return
        }
        val retryDelay = (RESTART_DELAY_MS * restartAttempts)
            .coerceAtMost(MAX_RESTART_DELAY_MS)
        if (!recreateRecognizerForRetry()) {
            finishWithError("The phone could not restart speech recognition.")
            return
        }

        onStatus(status)
        if (
            activeMode == VoiceSessionMode.SINGLE_NOTE &&
            singleSegments.hasFinalText &&
            !singleCompletionPending
        ) {
            scheduleSingleCompletion()
        }
        scheduleRestart(retryDelay)
    }

    private fun recreateRecognizerForRetry(): Boolean {
        val preferOnDevice = usingOnDeviceRecognizer && !standardFallbackAttempted
        releaseRecognizer()
        val replacement = createRecognizer(preferOnDevice) ?: return false
        recognizer = replacement
        recognizer?.setRecognitionListener(this)
        segmentFinalized = true
        return true
    }

    private fun isTransientError(error: Int): Boolean =
        error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))

    private fun completeSingle(includePartial: Boolean, stoppedByUser: Boolean = false) {
        if (mode != VoiceSessionMode.SINGLE_NOTE) {
            return
        }

        val transcript = singleSegments.transcript(includePartial)
        mode = null
        handler.removeCallbacksAndMessages(null)
        singleCompletionPending = false
        recognizer?.cancel()
        releaseRecognizer()
        singleSegments.reset()
        onPartial("")

        if (transcript.isNotBlank()) {
            onStatus(if (stoppedByUser) "Voice input stopped and saved" else "Voice input complete")
            onFinal(transcript)
        } else {
            onStatus("Voice input stopped")
        }
    }

    private fun finishWithError(message: String) {
        if (mode == VoiceSessionMode.SINGLE_NOTE && singleSegments.hasFinalText) {
            completeSingle(includePartial = true)
            return
        }

        val recoveredBrainDumpText = if (mode == VoiceSessionMode.BRAIN_DUMP) {
            brainDumpPartial.trim()
        } else {
            ""
        }
        mode = null
        handler.removeCallbacksAndMessages(null)
        singleCompletionPending = false
        singleSegments.reset()
        brainDumpPartial = ""
        releaseRecognizer()
        onPartial("")
        if (recoveredBrainDumpText.isNotBlank()) {
            onFinal(recoveredBrainDumpText)
        }
        onFailure(message)
    }

    private fun releaseRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun createRecognizer(preferOnDevice: Boolean): SpeechRecognizer? {
        if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrNull()?.let { created ->
                usingOnDeviceRecognizer = true
                return created
            }
            standardFallbackAttempted = true
        }

        usingOnDeviceRecognizer = false
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
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

    private fun listeningStatus(): String = when (mode) {
        VoiceSessionMode.BRAIN_DUMP -> "Brain Dump is listening…"
        VoiceSessionMode.SINGLE_NOTE -> if (singleSegments.hasFinalText) {
            singlePauseStatus()
        } else {
            "Listening…"
        }
        null -> "Voice input stopped"
    }

    private fun singlePauseStatus(): String =
        "Keep speaking, or pause 4 seconds to finish…"

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post(action)
        }
    }

    private companion object {
        const val RESTART_DELAY_MS = 250L
        const val MAX_RESTART_DELAY_MS = 3_000L
        const val MAX_SINGLE_TRANSIENT_RETRIES = 3
        const val SINGLE_COMPLETION_GRACE_MS = 4_000L
        const val POSSIBLY_COMPLETE_SILENCE_MS = 1_500L
        const val COMPLETE_SILENCE_MS = 2_500L
    }
}

/** Keeps recognizer sessions separate from the single command delivered to the app. */
internal class VoiceSegmentAccumulator {
    private val finalSegments = mutableListOf<String>()
    private var partialSegment = ""

    val hasFinalText: Boolean
        get() = finalSegments.isNotEmpty()

    val finalText: String
        get() = finalSegments.joinToString(" ")

    val previewText: String
        get() = transcript(includePartial = true)

    fun addFinal(segment: String) {
        val normalized = segment.trim()
        if (normalized.isNotBlank()) {
            finalSegments += normalized
        }
        partialSegment = ""
    }

    fun updatePartial(partial: String) {
        partialSegment = partial.trim()
    }

    fun transcript(includePartial: Boolean): String = buildList {
        addAll(finalSegments)
        if (includePartial && partialSegment.isNotBlank()) {
            add(partialSegment)
        }
    }.joinToString(" ")

    fun reset() {
        finalSegments.clear()
        partialSegment = ""
    }
}
