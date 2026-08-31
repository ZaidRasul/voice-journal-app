package com.voicejournal.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueryParserTest {
    @Test
    fun parsesShowEntriesAndRemovesJournalSuffix() {
        val query = VoiceQueryParser.parse("Show me all entries from weight journal")

        assertEquals(VoiceQuery.ShowEntries("weight"), query)
    }

    @Test
    fun parsesShowEntriesWithJournalPrefixAndCleansWhitespace() {
        val query = VoiceQueryParser.parse("list entries in the journal   Morning Pages!")

        assertEquals(VoiceQuery.ShowEntries("Morning Pages"), query)
    }

    @Test
    fun trendQueryUsesAnExactThirtyDayRange() {
        val now = 1_800_000_000_000L
        val query = VoiceQueryParser.parse(
            text = "How has my weight changed over the last month?",
            nowMillis = now
        )

        assertTrue(query is VoiceQuery.AnalyzeTrend)
        query as VoiceQuery.AnalyzeTrend
        assertEquals("weight", query.journalName)
        assertEquals(now, query.range.endInclusiveMillis)
        assertEquals(30L * 24L * 60L * 60L * 1_000L, now - query.range.startInclusiveMillis)
    }

    @Test
    fun parsesAlternativeTrendPhrasingAndCleansJournalSuffix() {
        val query = VoiceQueryParser.parse(
            text = "what's the trend for my Body Weight journal during last month.",
            nowMillis = 5_000_000_000L
        )

        assertTrue(query is VoiceQuery.AnalyzeTrend)
        assertEquals("Body Weight", (query as VoiceQuery.AnalyzeTrend).journalName)
    }

    @Test
    fun ordinaryDictationIsNotTreatedAsAQuery() {
        assertNull(VoiceQueryParser.parse("I felt stronger after my walk today"))
    }

    @Test
    fun incompleteQueryIsRejected() {
        assertNull(VoiceQueryParser.parse("show me all entries from journal"))
    }

    @Test
    fun dateSubtractionDoesNotUnderflow() {
        val query = VoiceQueryParser.parse(
            text = "how did my weight change over the last month",
            nowMillis = Long.MIN_VALUE
        ) as VoiceQuery.AnalyzeTrend

        assertEquals(Long.MIN_VALUE, query.range.startInclusiveMillis)
    }
}
