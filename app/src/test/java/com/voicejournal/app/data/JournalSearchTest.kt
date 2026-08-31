package com.voicejournal.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalSearchTest {
    private val weightJournal = JournalNote(
        id = 1L,
        title = "Weight Journal",
        createdAt = 100L,
        updatedAt = 300L,
        blocks = mutableListOf(
            NoteBlock(text = "72 kg", createdAt = 100L),
            NoteBlock(text = "71.5 kg", createdAt = 200L)
        )
    )

    private val ideasJournal = JournalNote(
        id = 2L,
        title = "Ideas",
        createdAt = 100L,
        updatedAt = 200L,
        blocks = mutableListOf(
            NoteBlock(text = "Try a new workout plan", createdAt = 150L),
            NoteBlock(text = "Read more", createdAt = 175L)
        )
    )

    @Test
    fun titleSearchIsCaseInsensitiveAndReturnsAllJournalEntries() {
        val result = JournalSearch.filter(
            listOf(weightJournal, ideasJournal),
            "  WEIGHT journal  "
        ).single()

        assertEquals(weightJournal, result.journal)
        assertTrue(result.matchedJournalTitle)
        assertEquals(weightJournal.blocks, result.entries)
    }

    @Test
    fun entrySearchIsCaseInsensitiveAndReturnsOnlyMatchingEntries() {
        val result = JournalSearch.filter(
            listOf(weightJournal, ideasJournal),
            "WORKOUT"
        ).single()

        assertEquals(ideasJournal, result.journal)
        assertFalse(result.matchedJournalTitle)
        assertEquals(listOf(ideasJournal.blocks.first()), result.entries)
    }

    @Test
    fun blankOrUnknownSearchHasNoResults() {
        assertTrue(JournalSearch.filter(listOf(weightJournal), "  ").isEmpty())
        assertTrue(JournalSearch.filter(listOf(weightJournal), "sleep").isEmpty())
    }
}
