package com.voicejournal.app.voice

import android.content.Context

/**
 * Persisted state shared by the Brain Dump foreground service and the UI.
 *
 * The transcript deliberately keeps the original `rant_draft` preference key
 * so an existing user's unsaved dictation survives the feature rename.
 */
object BrainDumpSession {
    const val ACTION_STATE_CHANGED =
        "com.voicejournal.app.action.BRAIN_DUMP_STATE_CHANGED"
    const val EXTRA_OPEN_BRAIN_DUMP = "open_brain_dump"

    const val PREFERENCES = "voice_journal_preferences"
    const val LEGACY_TRANSCRIPT_KEY = "rant_draft"
    private const val RUNNING_KEY = "brain_dump_running"
    private const val STATUS_KEY = "brain_dump_status"

    data class State(
        val transcript: String,
        val isRunning: Boolean,
        val status: String
    )

    fun read(context: Context): State {
        val preferences = preferences(context)
        return State(
            transcript = preferences.getString(LEGACY_TRANSCRIPT_KEY, "").orEmpty(),
            isRunning = preferences.getBoolean(RUNNING_KEY, false),
            status = preferences.getString(STATUS_KEY, "").orEmpty()
        )
    }

    fun readTranscript(context: Context): String = read(context).transcript

    fun isRunning(context: Context): Boolean = read(context).isRunning

    fun readStatus(context: Context): String = read(context).status

    fun clearTranscript(context: Context) {
        preferences(context).edit().remove(LEGACY_TRANSCRIPT_KEY).apply()
    }

    fun markNotRunning(context: Context, status: String) {
        updateServiceState(context, isRunning = false, status = status)
    }

    internal fun appendTranscript(context: Context, text: String): String {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return read(context).transcript
        }

        val preferences = preferences(context)
        val existing = preferences.getString(LEGACY_TRANSCRIPT_KEY, "").orEmpty()
        val updated = if (existing.isBlank()) {
            cleanText
        } else {
            existing.trimEnd() + "\n\n" + cleanText
        }
        preferences.edit().putString(LEGACY_TRANSCRIPT_KEY, updated).apply()
        return updated
    }

    internal fun updateServiceState(
        context: Context,
        isRunning: Boolean,
        status: String
    ) {
        preferences(context)
            .edit()
            .putBoolean(RUNNING_KEY, isRunning)
            .putString(STATUS_KEY, status)
            .apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
