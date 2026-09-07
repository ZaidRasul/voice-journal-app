package com.voicejournal.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSegmentAccumulatorTest {

    @Test
    fun `confirmed recognizer segments become one command`() {
        val accumulator = VoiceSegmentAccumulator()

        accumulator.addFinal("add to weight")
        accumulator.addFinal("seventy two kilograms")

        assertTrue(accumulator.hasFinalText)
        assertEquals("add to weight seventy two kilograms", accumulator.finalText)
    }

    @Test
    fun `preview retains confirmed words while showing the current partial`() {
        val accumulator = VoiceSegmentAccumulator()

        accumulator.addFinal("Today I want to remember")
        accumulator.updatePartial("the next thought")

        assertEquals(
            "Today I want to remember the next thought",
            accumulator.previewText
        )
        assertEquals("Today I want to remember", accumulator.finalText)
    }

    @Test
    fun `explicit stop transcript includes the latest partial`() {
        val accumulator = VoiceSegmentAccumulator()

        accumulator.addFinal("first sentence")
        accumulator.updatePartial("unfinished second sentence")

        assertEquals(
            "first sentence unfinished second sentence",
            accumulator.transcript(includePartial = true)
        )
    }

    @Test
    fun `adding a final replaces its matching partial`() {
        val accumulator = VoiceSegmentAccumulator()

        accumulator.updatePartial("a draft")
        accumulator.addFinal("a final phrase")

        assertEquals("a final phrase", accumulator.previewText)
    }

    @Test
    fun `reset clears all session state`() {
        val accumulator = VoiceSegmentAccumulator()
        accumulator.addFinal("some words")
        accumulator.updatePartial("more words")

        accumulator.reset()

        assertFalse(accumulator.hasFinalText)
        assertEquals("", accumulator.previewText)
    }
}
