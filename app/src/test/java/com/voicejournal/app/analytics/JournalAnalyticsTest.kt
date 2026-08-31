package com.voicejournal.app.analytics

import com.voicejournal.app.data.NoteBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalAnalyticsTest {
    @Test
    fun extractsFirstSignedDecimal() {
        assertEquals(-2.75, requireNotNull(JournalAnalytics.extractFirstNumber("down -2.75 kg, then 1 kg")), 0.0)
        assertEquals(0.5, requireNotNull(JournalAnalytics.extractFirstNumber("up +.5 kg")), 0.0)
        assertEquals(72.0, requireNotNull(JournalAnalytics.extractFirstNumber("weight: 72 kg")), 0.0)
    }

    @Test
    fun ignoresEmbeddedAndMalformedNumbers() {
        assertNull(JournalAnalytics.extractFirstNumber("abc12 and version 1.2.3"))
        assertNull(JournalAnalytics.extractFirstNumber("no measurement today"))
    }

    @Test
    fun sortsPointsAndComputesSummary() {
        val summary = JournalAnalytics.analyze(
            blocks = listOf(
                NoteBlock(text = "74.25 kg", createdAt = 300L),
                NoteBlock(text = "starting at 72.5 kg", createdAt = 100L),
                NoteBlock(text = "73 kg", createdAt = 200L)
            )
        )

        requireNotNull(summary)
        assertEquals(listOf(100L, 200L, 300L), summary.points.map(AnalyticsPoint::timestamp))
        assertEquals(listOf(72.5, 73.0, 74.25), summary.points.map(AnalyticsPoint::value))
        assertEquals(72.5, summary.startValue, 0.0)
        assertEquals(74.25, summary.endValue, 0.0)
        assertEquals(1.75, summary.change, 0.0)
        assertEquals(72.5, summary.min, 0.0)
        assertEquals(74.25, summary.max, 0.0)
    }

    @Test
    fun filtersToInclusiveTimeRangeAndSkipsNonNumericBlocks() {
        val summary = JournalAnalytics.analyze(
            blocks = listOf(
                NoteBlock(text = "10", createdAt = 99L),
                NoteBlock(text = "11", createdAt = 100L),
                NoteBlock(text = "nothing numeric", createdAt = 150L),
                NoteBlock(text = "13", createdAt = 200L),
                NoteBlock(text = "14", createdAt = 201L)
            ),
            since = 100L,
            until = 200L
        )

        requireNotNull(summary)
        assertEquals(listOf(11.0, 13.0), summary.points.map(AnalyticsPoint::value))
        assertEquals(2.0, summary.change, 0.0)
    }

    @Test
    fun singleObservationHasZeroChange() {
        val summary = JournalAnalytics.analyze(
            listOf(NoteBlock(text = "-8.5", createdAt = 123L))
        )

        requireNotNull(summary)
        assertEquals(-8.5, summary.startValue, 0.0)
        assertEquals(-8.5, summary.endValue, 0.0)
        assertEquals(0.0, summary.change, 0.0)
        assertEquals(-8.5, summary.min, 0.0)
        assertEquals(-8.5, summary.max, 0.0)
    }

    @Test
    fun returnsNullWhenNoUsablePointsExist() {
        assertNull(JournalAnalytics.analyze(emptyList()))
        assertNull(JournalAnalytics.analyze(listOf(NoteBlock(text = "rest day", createdAt = 1L))))
        assertNull(
            JournalAnalytics.analyze(
                listOf(NoteBlock(text = "42", createdAt = 1L)),
                since = 10L,
                until = 5L
            )
        )
    }

    @Test
    fun returnedPointsDoNotExposeMutableInputState() {
        val block = NoteBlock(text = "70 kg", createdAt = 1L)
        val summary = requireNotNull(JournalAnalytics.analyze(listOf(block)))

        block.text = "99 kg"

        assertEquals("70 kg", summary.points.single().sourceText)
        assertEquals(70.0, summary.points.single().value, 0.0)
        assertTrue(summary.points !== listOf(block))
    }
}
