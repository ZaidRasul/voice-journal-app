package com.voicejournal.app.voice

import java.util.Locale

enum class JournalManagementOperation {
    CREATE,
    DELETE
}

sealed interface JournalManagementCommand {
    data class Create(val journalTitle: String) : JournalManagementCommand
    data class Delete(val journalTitle: String) : JournalManagementCommand
}

enum class JournalManagementInvalidReason {
    ALREADY_EXISTS,
    NOT_FOUND,
    AMBIGUOUS,
    UNSAFE_TARGET,
    REQUIRE_JOURNAL_WORD,
    MALFORMED
}

sealed interface JournalManagementResolution {
    data class Complete(val command: JournalManagementCommand) : JournalManagementResolution
    data class Incomplete(val operation: JournalManagementOperation) : JournalManagementResolution
    data class Invalid(
        val operation: JournalManagementOperation,
        val requestedTitle: String,
        val reason: JournalManagementInvalidReason
    ) : JournalManagementResolution

    data object NotACommand : JournalManagementResolution
}

/** Deterministic grammar for creating one journal or requesting one deletion. */
object JournalManagementVoiceParser {
    private val createPrefix = Regex(
        """^\s*(?:please\s+)?(?:create|make)\s+(?:(?:a|the)\s+)?(?:new\s+)?journal\b(.*?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val deleteVerb = Regex(
        """^\s*(?:please\s+)?(?:delete|remove)\s+(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val deletePrefix = Regex(
        """^(?:(?:the|my)\s+)?journal(?:\s+(?:called|named|titled))?\b\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )
    private val deleteSuffix = Regex(
        """^(.+?)\s+journal\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val leadingNameMarker = Regex(
        """^(?:called|named|titled)\b\s*""",
        RegexOption.IGNORE_CASE
    )
    private val chainedCommand = Regex(
        """\b(?:and|then)\s+(?:create|make|delete|remove|add|put|write|append|record|save|log)\b""",
        RegexOption.IGNORE_CASE
    )

    fun resolve(
        spoken: String,
        knownJournalTitles: Collection<String>
    ): JournalManagementResolution {
        createPrefix.matchEntire(spoken)?.let { match ->
            return resolveCreate(match.groupValues[1], knownJournalTitles)
        }

        val deleteMatch = deleteVerb.matchEntire(spoken)
            ?: return JournalManagementResolution.NotACommand
        return resolveDelete(deleteMatch.groupValues[1], knownJournalTitles)
    }

    private fun resolveCreate(
        rawRemainder: String,
        knownTitles: Collection<String>
    ): JournalManagementResolution {
        val withoutMarker = rawRemainder
            .trim()
            .trimStart(':', ';', '-', '–', '—')
            .trim()
            .replaceFirst(leadingNameMarker, "")
        val title = cleanTitle(withoutMarker)
        if (title.isBlank()) {
            return JournalManagementResolution.Incomplete(JournalManagementOperation.CREATE)
        }
        if (title.length > MAX_TITLE_LENGTH || chainedCommand.containsMatchIn(title)) {
            return JournalManagementResolution.Invalid(
                JournalManagementOperation.CREATE,
                title,
                JournalManagementInvalidReason.MALFORMED
            )
        }

        return when (val match = resolveKnownTitle(listOf(title), knownTitles)) {
            is KnownTitleMatch.One -> JournalManagementResolution.Invalid(
                JournalManagementOperation.CREATE,
                match.canonicalTitle,
                JournalManagementInvalidReason.ALREADY_EXISTS
            )

            KnownTitleMatch.Many -> JournalManagementResolution.Invalid(
                JournalManagementOperation.CREATE,
                title,
                JournalManagementInvalidReason.AMBIGUOUS
            )

            KnownTitleMatch.None -> JournalManagementResolution.Complete(
                JournalManagementCommand.Create(title)
            )
        }
    }

    private fun resolveDelete(
        rawBody: String,
        knownTitles: Collection<String>
    ): JournalManagementResolution {
        val body = cleanTitle(rawBody)
        if (body.isBlank()) {
            return JournalManagementResolution.Incomplete(JournalManagementOperation.DELETE)
        }
        if (isUnsafeDeleteTarget(body)) {
            return JournalManagementResolution.Invalid(
                JournalManagementOperation.DELETE,
                body,
                JournalManagementInvalidReason.UNSAFE_TARGET
            )
        }

        val prefixMatch = deletePrefix.matchEntire(body)
        val suffixMatch = deleteSuffix.matchEntire(body)
        if (prefixMatch == null && suffixMatch == null) {
            val titleMatch = resolveKnownTitle(listOf(body), knownTitles)
            return if (titleMatch != KnownTitleMatch.None) {
                JournalManagementResolution.Invalid(
                    JournalManagementOperation.DELETE,
                    body,
                    JournalManagementInvalidReason.REQUIRE_JOURNAL_WORD
                )
            } else {
                JournalManagementResolution.NotACommand
            }
        }

        val candidates = buildList {
            if (prefixMatch != null) {
                add(cleanTitle(prefixMatch.groupValues[1]))
            }
            if (suffixMatch != null) {
                // Try the complete body first so a real title ending in "Journal" wins.
                add(body)
                add(cleanTitle(suffixMatch.groupValues[1]))
                removeLeadingOwner(suffixMatch.groupValues[1])?.let { add(cleanTitle(it)) }
            }
            removeLeadingOwner(body)?.let { add(cleanTitle(it)) }
        }.filter { it.isNotBlank() }.distinctBy { normalize(it) }

        if (candidates.isEmpty()) {
            return JournalManagementResolution.Incomplete(JournalManagementOperation.DELETE)
        }
        if (candidates.any(::isUnsafeDeleteTarget)) {
            return JournalManagementResolution.Invalid(
                JournalManagementOperation.DELETE,
                candidates.first(),
                JournalManagementInvalidReason.UNSAFE_TARGET
            )
        }

        return when (val match = resolveKnownTitle(candidates, knownTitles)) {
            is KnownTitleMatch.One -> JournalManagementResolution.Complete(
                JournalManagementCommand.Delete(match.canonicalTitle)
            )

            KnownTitleMatch.Many -> JournalManagementResolution.Invalid(
                JournalManagementOperation.DELETE,
                candidates.first(),
                JournalManagementInvalidReason.AMBIGUOUS
            )

            KnownTitleMatch.None -> JournalManagementResolution.Invalid(
                JournalManagementOperation.DELETE,
                candidates.first(),
                JournalManagementInvalidReason.NOT_FOUND
            )
        }
    }

    private fun resolveKnownTitle(
        candidates: List<String>,
        knownTitles: Collection<String>
    ): KnownTitleMatch {
        val aliases = knownTitles.mapIndexedNotNull { index, rawTitle ->
            val canonical = rawTitle.trim()
            canonical.takeIf { it.isNotBlank() }?.let { KnownTitle(index, canonical) }
        }

        for (candidate in candidates) {
            val normalizedCandidate = normalize(candidate)
            val exactMatches = aliases.filter { normalize(it.canonicalTitle) == normalizedCandidate }
            if (exactMatches.isNotEmpty()) {
                return exactMatches.toMatch()
            }

            val aliasMatches = aliases.filter { known ->
                known.generatedAliases.any { normalize(it) == normalizedCandidate }
            }
            if (aliasMatches.isNotEmpty()) {
                return aliasMatches.toMatch()
            }
        }
        return KnownTitleMatch.None
    }

    private fun List<KnownTitle>.toMatch(): KnownTitleMatch {
        val distinctRows = distinctBy { it.rowIndex }
        return if (distinctRows.size == 1) {
            KnownTitleMatch.One(distinctRows.single().canonicalTitle)
        } else {
            KnownTitleMatch.Many
        }
    }

    private data class KnownTitle(
        val rowIndex: Int,
        val canonicalTitle: String
    ) {
        val generatedAliases: List<String>
            get() = buildList {
                val spokenTitle = cleanTitle(canonicalTitle)
                if (!spokenTitle.equals(canonicalTitle, ignoreCase = true)) {
                    add(spokenTitle)
                }
                listOf(canonicalTitle, spokenTitle).distinctBy(::normalize).forEach { title ->
                    if (title.endsWith(" Journal", ignoreCase = true)) {
                        add(title.dropLast(" Journal".length).trim())
                    } else {
                        add("$title Journal")
                    }
                }
            }.filter { it.isNotBlank() }.distinctBy(::normalize)
    }

    private sealed interface KnownTitleMatch {
        data class One(val canonicalTitle: String) : KnownTitleMatch
        data object Many : KnownTitleMatch
        data object None : KnownTitleMatch
    }

    private fun cleanTitle(raw: String): String = raw
        .trim()
        .trimEnd('.', ',', '!', '?', ':', ';')
        .trim()
        .trim('"', '\'', '‘', '’', '“', '”')
        .trim()
        .trimEnd('.', ',', '!', '?', ':', ';')
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun removeLeadingOwner(value: String): String? {
        val match = Regex("""^\s*(?:the|my)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value)
            ?: return null
        return match.groupValues[1]
    }

    private fun isUnsafeDeleteTarget(title: String): Boolean {
        val normalized = normalize(title)
        return normalized in UNSAFE_DELETE_TARGETS ||
            BULK_DELETE_TARGET.matches(normalized)
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private const val MAX_TITLE_LENGTH = 120
    private val BULK_DELETE_TARGET = Regex(
        """^(?:(?:all|every|each)(?:\s+of)?\s+(?:(?:the|my)\s+)?journals?|(?:my|the)\s+journals)$"""
    )
    private val UNSAFE_DELETE_TARGETS = setOf(
        "all",
        "all journal",
        "all journals",
        "every",
        "every journal",
        "every journals",
        "everything",
        "journals"
    )
}
