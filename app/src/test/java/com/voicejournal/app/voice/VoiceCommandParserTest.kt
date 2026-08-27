package com.voicejournal.app.voice

import com.voicejournal.app.data.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun labelledCommandCreatesCheckboxForNamedNote() {
        val command = VoiceCommandParser.parse("add to Groceries: checkbox milk")

        assertNotNull(command)
        assertEquals("Groceries", command?.noteTitle)
        assertEquals(BlockType.CHECKBOX, command?.block?.type)
        assertEquals("milk", command?.block?.text)
    }

    @Test
    fun naturalCommandCreatesNumberedBlock() {
        val command = VoiceCommandParser.parse("put 72 kilograms in Weight log")

        assertNotNull(command)
        assertEquals("Weight log", command?.noteTitle)
        assertEquals(BlockType.TEXT, command?.block?.type)
        assertEquals("72 kilograms", command?.block?.text)
    }

    @Test
    fun invalidSpeechIsNotTreatedAsACommand() {
        assertEquals(null, VoiceCommandParser.parse("this is just a thought"))
    }
}
