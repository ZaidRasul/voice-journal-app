package com.voicejournal.app.analytics

import com.voicejournal.app.data.NoteBlock

data class AnalyticsPoint(
    val timestamp: Long,
    val value: Double,
    val sourceText: String
)

data class AnalyticsSummary(
    val points: List<AnalyticsPoint>,
    val startValue: Double,
    val endValue: Double,
    val change: Double,
    val min: Double,
    val max: Double
)

/** Extracts numeric journal observations and summarizes them chronologically. */
object JournalAnalytics {
    private val signedDecimal = Regex("""(?<![\w.])[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?![\w.])""")

    fun analyze(
        blocks: List<NoteBlock>,
        since: Long = Long.MIN_VALUE,
        until: Long = Long.MAX_VALUE
    ): AnalyticsSummary? {
        if (since > until) return null

        val points = blocks.mapNotNull { block ->
            if (block.createdAt !in since..until) return@mapNotNull null

            extractFirstNumber(block.text)?.let { value ->
                AnalyticsPoint(
                    timestamp = block.createdAt,
                    value = value,
                    sourceText = block.text
                )
            }
        }.sortedBy(AnalyticsPoint::timestamp)

        if (points.isEmpty()) return null

        val startValue = points.first().value
        val endValue = points.last().value
        return AnalyticsSummary(
            points = points,
            startValue = startValue,
            endValue = endValue,
            change = endValue - startValue,
            min = points.minOf(AnalyticsPoint::value),
            max = points.maxOf(AnalyticsPoint::value)
        )
    }

    fun extractFirstNumber(text: String): Double? = signedDecimal
        .find(text)
        ?.value
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
}
