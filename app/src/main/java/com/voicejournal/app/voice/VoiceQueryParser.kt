package com.voicejournal.app.voice

/** A read-only request understood by the journal's deterministic voice grammar. */
sealed interface VoiceQuery {
    data class ShowEntries(val journalName: String) : VoiceQuery

    data class AnalyzeTrend(
        val journalName: String,
        val range: QueryTimeRange
    ) : VoiceQuery
}

data class QueryTimeRange(
    val startInclusiveMillis: Long,
    val endInclusiveMillis: Long
)

/**
 * Parses the small set of retrieval commands supported by the app.
 *
 * Keeping this grammar deterministic prevents ordinary dictated journal text
 * from accidentally being treated as a query.
 */
object VoiceQueryParser {
    const val LAST_MONTH_DAYS: Int = 30

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    private const val LAST_MONTH_MILLIS = LAST_MONTH_DAYS * MILLIS_PER_DAY

    private val showEntriesFormat = Regex(
        """^\s*(?:show|list|get|find)(?:\s+me)?(?:\s+all)?\s+(?:the\s+)?entries\s+(?:from|in)\s+(.+?)\s*[.!?]*\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val changedFormat = Regex(
        """^\s*(?:show\s+me\s+)?how\s+(?:has|did)\s+(?:my\s+|the\s+)?(.+?)\s+(?:changed|change)\s+(?:over|in|during)\s+(?:the\s+)?last\s+month\s*[.!?]*\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val trendFormat = Regex(
        """^\s*(?:show\s+me\s+)?(?:what(?:'s|\s+is)\s+)?(?:the\s+)?trend\s+(?:for|in|of)\s+(?:my\s+|the\s+)?(.+?)\s+(?:over|in|during)\s+(?:the\s+)?last\s+month\s*[.!?]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(
        text: String,
        nowMillis: Long = System.currentTimeMillis()
    ): VoiceQuery? {
        showEntriesFormat.matchEntire(text)?.let { match ->
            val journalName = cleanJournalName(match.groupValues[1])
            return journalName.takeIf(String::isNotBlank)?.let(VoiceQuery::ShowEntries)
        }

        val trendMatch = changedFormat.matchEntire(text) ?: trendFormat.matchEntire(text)
        if (trendMatch != null) {
            val journalName = cleanJournalName(trendMatch.groupValues[1])
            if (journalName.isBlank()) return null

            return VoiceQuery.AnalyzeTrend(
                journalName = journalName,
                range = QueryTimeRange(
                    startInclusiveMillis = subtractWithoutUnderflow(
                        value = nowMillis,
                        amount = LAST_MONTH_MILLIS
                    ),
                    endInclusiveMillis = nowMillis
                )
            )
        }

        return null
    }

    private fun cleanJournalName(rawName: String): String {
        var name = rawName
            .trim()
            .trim('"', '\'', '‘', '’', '“', '”')
            .trim()
            .replace(Regex("""\s+"""), " ")

        name = name.replace(Regex("""^(?:the\s+)?journal\s+""", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("""\s+journal$""", RegexOption.IGNORE_CASE), "")

        return name
            .trim()
            .trimEnd('.', ',', '!', '?', ':', ';')
            .trim()
    }

    private fun subtractWithoutUnderflow(value: Long, amount: Long): Long =
        if (value < Long.MIN_VALUE + amount) Long.MIN_VALUE else value - amount
}
