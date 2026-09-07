package com.voicejournal.app.voice

import com.voicejournal.app.data.BlockType
import com.voicejournal.app.data.NoteBlock
import java.util.Locale

data class VoiceNoteCommand(
    val noteTitle: String,
    val block: NoteBlock
)

/**
 * Result of resolving speech with the journals that currently exist.
 *
 * [Incomplete] and [UnresolvedTarget] are deliberately different from
 * [NotACommand]: callers should ask the user for clarification instead of
 * saving command words in the default journal.
 */
sealed interface VoiceCommandResolution {
    data class Complete(val command: VoiceNoteCommand) : VoiceCommandResolution
    data class Incomplete(val noteTitle: String) : VoiceCommandResolution
    data class UnresolvedTarget(val spokenRemainder: String) : VoiceCommandResolution
    data object NotACommand : VoiceCommandResolution
}

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
    private val targetFirstPrefix = Regex(
        """^\s*(?:add|put|write|append|record|save|log)\s+(?:this\s+)?(?:entry\s+)?(?:in|into|to)\b\s*(?:the\s+)?""",
        RegexOption.IGNORE_CASE
    )
    private val leadingJournalDescriptor = Regex(
        """^\s*(?:journal|note)\s+""",
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

    /**
     * Resolves commands against the user's real journal titles.
     *
     * This adds support for target-first speech without a colon, for example
     * "add to Weight 72 kilograms". Titles are matched case-insensitively and
     * longest-first, so a journal named "Weight Log" wins over "Weight".
     * Existing colon-labelled and target-last commands retain their original
     * behavior, including their ability to name a new journal.
     */
    fun resolve(
        spoken: String,
        knownJournalTitles: Collection<String>
    ): VoiceCommandResolution {
        val titleAliases = knownTitleAliases(knownJournalTitles)

        // A colon makes the title/content boundary explicit and may name a new journal.
        if (labelledFormat.matches(spoken)) {
            val parsed = parse(spoken) ?: return VoiceCommandResolution.NotACommand
            return VoiceCommandResolution.Complete(
                parsed.withCanonicalTitle(titleAliases)
            )
        }

        val prefix = targetFirstPrefix.find(spoken)
            ?.takeIf { it.range.first == 0 }
        if (prefix == null) {
            val parsed = parse(spoken) ?: return VoiceCommandResolution.NotACommand
            return VoiceCommandResolution.Complete(parsed.withCanonicalTitle(titleAliases))
        }
        val afterPrefix = spoken.substring(prefix.range.last + 1)

        val candidateRemainders = buildList {
            add(afterPrefix)
            leadingJournalDescriptor.find(afterPrefix)?.let { descriptor ->
                add(afterPrefix.substring(descriptor.range.last + 1))
            }
        }
        val (matchedRemainder, matchedTitle) = candidateRemainders.firstNotNullOfOrNull { remainder ->
            titleAliases
                .firstOrNull { title -> titleMatchesStartOf(remainder, title.spokenAlias) }
                ?.let { remainder to it }
        } ?: return VoiceCommandResolution.UnresolvedTarget(afterPrefix.cleanSpokenRemainder())

        val titleMatch = titleAtStartRegex(matchedTitle.spokenAlias).find(matchedRemainder)
            ?: return VoiceCommandResolution.UnresolvedTarget(afterPrefix.cleanSpokenRemainder())
        val content = matchedRemainder
            .substring(titleMatch.range.last + 1)
            .trim()
            .trimStart(':', ',', ';', '-', '\u2013', '\u2014')
            .trim()

        if (content.isBlank() || content.all { !it.isLetterOrDigit() }) {
            return VoiceCommandResolution.Incomplete(matchedTitle.canonicalTitle)
        }

        val block = content.toBlock()
        return if (block.text.isBlank()) {
            VoiceCommandResolution.Incomplete(matchedTitle.canonicalTitle)
        } else {
            VoiceCommandResolution.Complete(
                VoiceNoteCommand(matchedTitle.canonicalTitle, block)
            )
        }
    }

    /** Converts untargeted speech into a typed entry for the default journal. */
    fun parseEntry(spoken: String): NoteBlock = spoken.toBlock()

    private fun String.cleanTitle(): String =
        trim()
            .trimEnd('.', ',', '!', '?')
            .replace(Regex("\\s+"), " ")

    private fun String.cleanSpokenRemainder(): String =
        trim().trimEnd('.', ',', '!', '?').trim()

    private data class KnownTitleAlias(
        val canonicalTitle: String,
        val spokenAlias: String,
        val isExact: Boolean
    )

    private fun VoiceNoteCommand.withCanonicalTitle(
        aliases: List<KnownTitleAlias>
    ): VoiceNoteCommand {
        val canonicalTitle = aliases
            .firstOrNull { it.spokenAlias.equals(noteTitle, ignoreCase = true) }
            ?.canonicalTitle
        return if (canonicalTitle == null) this else copy(noteTitle = canonicalTitle)
    }

    private fun knownTitleAliases(titles: Collection<String>): List<KnownTitleAlias> =
        buildList {
            titles.forEach { rawTitle ->
                val canonicalTitle = rawTitle.trim()
                val title = canonicalTitle.cleanTitle()
                if (title.isBlank()) return@forEach
                add(KnownTitleAlias(canonicalTitle, title, isExact = true))

                val withoutJournal = title.replace(
                    Regex("""\s+journal$""", RegexOption.IGNORE_CASE),
                    ""
                )
                if (withoutJournal != title && withoutJournal.isNotBlank()) {
                    add(KnownTitleAlias(canonicalTitle, withoutJournal, isExact = false))
                } else {
                    add(KnownTitleAlias(canonicalTitle, "$title Journal", isExact = false))
                }
            }
        }
            .distinctBy {
                "${it.canonicalTitle.lowercase(Locale.ROOT)}\u0000${it.spokenAlias.lowercase(Locale.ROOT)}"
            }
            .sortedWith(
                compareByDescending<KnownTitleAlias> { it.spokenAlias.length }
                    .thenByDescending { it.isExact }
            )

    private fun titleMatchesStartOf(spokenRemainder: String, title: String): Boolean =
        titleAtStartRegex(title).containsMatchIn(spokenRemainder)

    private fun titleAtStartRegex(title: String): Regex {
        val flexibleWhitespaceTitle = title
            .split(Regex("\\s+"))
            .joinToString("\\s+") { Regex.escape(it) }
        return Regex(
            """^\s*$flexibleWhitespaceTitle(?=\s|[:;,\-.!?]|$)""",
            RegexOption.IGNORE_CASE
        )
    }

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
