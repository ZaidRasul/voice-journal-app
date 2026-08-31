package com.voicejournal.app.voice

import com.voicejournal.app.data.BlockType
import com.voicejournal.app.data.NoteBlock
import java.util.Locale

data class VoiceNoteCommand(
    val noteTitle: String,
    val block: NoteBlock
)

/**
 * A small deterministic grammar is safer than treating dictated text as an
 * unrestricted assistant command. It is also straightforward to test.
 */
object VoiceCommandParser {
    private val labelledFormat = Regex(
        """^\s*(?:add|put|write|append)\s+to\s+(.+?)\s*:\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val naturalFormat = Regex(
        """^\s*(?:add|put|write|append)\s+(.+?)\s+(?:in|into|to)\s+(?:the\s+)?(?:note\s+)?(.+?)\s*[.!?]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(spoken: String): VoiceNoteCommand? {
        val labelled = labelledFormat.matchEntire(spoken)
        val natural = naturalFormat.matchEntire(spoken)

        val title: String
        val content: String
        when {
            labelled != null -> {
                title = labelled.groupValues[1]
                content = labelled.groupValues[2]
            }

            natural != null -> {
                content = natural.groupValues[1]
                title = natural.groupValues[2]
            }

            else -> return null
        }

        val cleanedTitle = title.cleanTitle()
        val block = content.toBlock()
        return if (cleanedTitle.isBlank() || block.text.isBlank()) {
            null
        } else {
            VoiceNoteCommand(cleanedTitle, block)
        }
    }

    /** Converts untargeted speech into a typed entry for the default journal. */
    fun parseEntry(spoken: String): NoteBlock = spoken.toBlock()

    private fun String.cleanTitle(): String =
        trim()
            .trimEnd('.', ',', '!', '?')
            .replace(Regex("\\s+"), " ")

    private fun String.toBlock(): NoteBlock {
        val cleaned = trim()
        val prefix = cleaned.lowercase(Locale.ROOT)
        val choices = listOf(
            "checkbox " to BlockType.CHECKBOX,
            "check box " to BlockType.CHECKBOX,
            "checklist " to BlockType.CHECKBOX,
            "bulleted " to BlockType.BULLET,
            "bullet " to BlockType.BULLET,
            "numbered " to BlockType.NUMBERED,
            "number " to BlockType.NUMBERED,
            "text " to BlockType.TEXT
        )
        val choice = choices.firstOrNull { prefix.startsWith(it.first) }
        return if (choice == null) {
            NoteBlock(type = BlockType.TEXT, text = cleaned)
        } else {
            NoteBlock(type = choice.second, text = cleaned.drop(choice.first.length).trim())
        }
    }
}
