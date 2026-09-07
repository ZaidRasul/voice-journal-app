package com.voicejournal.app.voice

import com.voicejournal.app.data.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun naturalCommandCreatesPlainTextBlockWithoutTypePrefix() {
        val command = VoiceCommandParser.parse("put 72 kilograms in Weight log")

        assertNotNull(command)
        assertEquals("Weight log", command?.noteTitle)
        assertEquals(BlockType.TEXT, command?.block?.type)
        assertEquals("72 kilograms", command?.block?.text)
    }

    @Test
    fun untargetedContentCanCreateTypedDefaultJournalEntry() {
        val block = VoiceCommandParser.parseEntry("numbered 72 kilograms")

        assertEquals(BlockType.NUMBERED, block.type)
        assertEquals("72 kilograms", block.text)
    }

    @Test
    fun invalidSpeechIsNotTreatedAsACommand() {
        assertEquals(null, VoiceCommandParser.parse("this is just a thought"))
    }

    @Test
    fun targetFirstCommandUsesAnyExistingJournalWithoutAColon() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to Dream Experiments lucid dream after coffee",
            knownJournalTitles = listOf("Default Journal", "Dream Experiments")
        )

        assertTrue(result is VoiceCommandResolution.Complete)
        val command = (result as VoiceCommandResolution.Complete).command
        assertEquals("Dream Experiments", command.noteTitle)
        assertEquals("lucid dream after coffee", command.block.text)
    }

    @Test
    fun targetFirstJournalWinsWhenEntryContainsTheWordTo() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to Weight walked to work",
            knownJournalTitles = listOf("Weight")
        )

        assertTrue(result is VoiceCommandResolution.Complete)
        val command = (result as VoiceCommandResolution.Complete).command
        assertEquals("Weight", command.noteTitle)
        assertEquals("walked to work", command.block.text)
    }

    @Test
    fun targetFirstCommandCanCreateTypedEntry() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to weight checkbox record morning weight",
            knownJournalTitles = listOf("Weight")
        )

        assertTrue(result is VoiceCommandResolution.Complete)
        val command = (result as VoiceCommandResolution.Complete).command
        assertEquals("Weight", command.noteTitle)
        assertEquals(BlockType.CHECKBOX, command.block.type)
        assertEquals("record morning weight", command.block.text)
    }

    @Test
    fun longestExistingJournalTitleWins() {
        val result = VoiceCommandParser.resolve(
            spoken = "record in Weight Log 72 kilograms",
            knownJournalTitles = listOf("Weight", "Weight Log")
        )

        assertTrue(result is VoiceCommandResolution.Complete)
        val command = (result as VoiceCommandResolution.Complete).command
        assertEquals("Weight Log", command.noteTitle)
        assertEquals("72 kilograms", command.block.text)
    }

    @Test
    fun existingJournalCommandWithoutContentIsIncomplete() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to weight.",
            knownJournalTitles = listOf("Default Journal", "Weight")
        )

        assertEquals(VoiceCommandResolution.Incomplete("Weight"), result)
    }

    @Test
    fun commandLikeSpeechWithUnknownTargetNeedsClarification() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to Exercise twenty minutes of walking",
            knownJournalTitles = listOf("Default Journal", "Weight")
        )

        assertEquals(
            VoiceCommandResolution.UnresolvedTarget("Exercise twenty minutes of walking"),
            result
        )
    }

    @Test
    fun unfinishedCommandWithoutEvenATitleNeedsClarification() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to",
            knownJournalTitles = listOf("Default Journal", "Weight")
        )

        assertEquals(VoiceCommandResolution.UnresolvedTarget(""), result)
    }

    @Test
    fun journalSuffixMayBeOmittedWhenSpeakingExistingTitle() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to Gratitude three good things happened",
            knownJournalTitles = listOf("Gratitude Journal")
        )

        assertTrue(result is VoiceCommandResolution.Complete)
        val command = (result as VoiceCommandResolution.Complete).command
        assertEquals("Gratitude Journal", command.noteTitle)
        assertEquals("three good things happened", command.block.text)
    }

    @Test
    fun journalSuffixMayBeSpokenWhenItIsNotInStoredTitle() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to Weight Journal 72 kilograms",
            knownJournalTitles = listOf("Weight")
        )

        assertTrue(result is VoiceCommandResolution.Complete)
        val command = (result as VoiceCommandResolution.Complete).command
        assertEquals("Weight", command.noteTitle)
        assertEquals("72 kilograms", command.block.text)
    }

    @Test
    fun originalTargetLastGrammarStillResolves() {
        val result = VoiceCommandParser.resolve(
            spoken = "put 72 kilograms in weight log",
            knownJournalTitles = listOf("Weight Log")
        )

        assertTrue(result is VoiceCommandResolution.Complete)
        val command = (result as VoiceCommandResolution.Complete).command
        assertEquals("Weight Log", command.noteTitle)
        assertEquals("72 kilograms", command.block.text)
    }

    @Test
    fun ordinaryDictationIsNotMistakenForACommand() {
        val result = VoiceCommandParser.resolve(
            spoken = "I want to add more movement to my day",
            knownJournalTitles = listOf("Health")
        )

        assertEquals(VoiceCommandResolution.NotACommand, result)
    }

    @Test
    fun articleBeforeEntryIsAcceptedInTargetFirstCommand() {
        val result = VoiceCommandParser.resolve(
            spoken = "add an entry to Weight 72 kilograms",
            knownJournalTitles = listOf("Weight")
        ) as VoiceCommandResolution.Complete

        assertEquals("Weight", result.command.noteTitle)
        assertEquals("72 kilograms", result.command.block.text)
    }

    @Test
    fun titleBeginningWithTheIsNotTreatedAsAnOptionalArticle() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to The Weight 72 kilograms",
            knownJournalTitles = listOf("The Weight", "Weight")
        ) as VoiceCommandResolution.Complete

        assertEquals("The Weight", result.command.noteTitle)
        assertEquals("72 kilograms", result.command.block.text)
    }

    @Test
    fun shortGeneratedJournalAliasDoesNotHijackContent() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to my weight is 72",
            knownJournalTitles = listOf("My Journal", "Weight")
        )

        assertTrue(result is VoiceCommandResolution.UnresolvedTarget)
    }

    @Test
    fun additionalVerbWorksForTargetLastCommand() {
        val result = VoiceCommandParser.resolve(
            spoken = "record 72 kilograms in Weight",
            knownJournalTitles = listOf("Weight")
        ) as VoiceCommandResolution.Complete

        assertEquals("Weight", result.command.noteTitle)
        assertEquals("72 kilograms", result.command.block.text)
    }

    @Test
    fun politeTargetFirstCommandDoesNotFallIntoDefault() {
        val result = VoiceCommandParser.resolve(
            spoken = "please add to Weight 72 kilograms",
            knownJournalTitles = listOf("Weight")
        ) as VoiceCommandResolution.Complete

        assertEquals("Weight", result.command.noteTitle)
        assertEquals("72 kilograms", result.command.block.text)
    }

    @Test
    fun longestKnownTitleMayContainAColon() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to Work: Ideas project thought",
            knownJournalTitles = listOf("Work", "Work: Ideas")
        ) as VoiceCommandResolution.Complete

        assertEquals("Work: Ideas", result.command.noteTitle)
        assertEquals("project thought", result.command.block.text)
    }

    @Test
    fun recognitionPunctuationBetweenTitleAndContentIsRemoved() {
        val result = VoiceCommandParser.resolve(
            spoken = "add to Weight. 72 kilograms",
            knownJournalTitles = listOf("Weight")
        ) as VoiceCommandResolution.Complete

        assertEquals("72 kilograms", result.command.block.text)
    }
}
