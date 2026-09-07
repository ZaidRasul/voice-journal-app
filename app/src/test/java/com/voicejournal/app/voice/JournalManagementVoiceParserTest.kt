package com.voicejournal.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalManagementVoiceParserTest {
    @Test
    fun createsNamedJournal() {
        val result = resolve("create a new journal called Travel Plans")

        assertEquals(
            JournalManagementResolution.Complete(
                JournalManagementCommand.Create("Travel Plans")
            ),
            result
        )
    }

    @Test
    fun makeAndNamedVariantCleansQuotesAndPunctuation() {
        val result = resolve("please make journal named “Morning Pages”.")

        assertEquals(
            JournalManagementResolution.Complete(
                JournalManagementCommand.Create("Morning Pages")
            ),
            result
        )
    }

    @Test
    fun duplicateCreateIsRejected() {
        val result = resolve("create journal Weight", listOf("Weight"))

        assertTrue(result is JournalManagementResolution.Invalid)
        assertEquals(
            JournalManagementInvalidReason.ALREADY_EXISTS,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun aliasCollisionAlsoRejectsCreate() {
        val result = resolve("create journal Weight", listOf("Weight Journal"))

        assertTrue(result is JournalManagementResolution.Invalid)
    }

    @Test
    fun missingCreateNameIsIncomplete() {
        assertEquals(
            JournalManagementResolution.Incomplete(JournalManagementOperation.CREATE),
            resolve("create a new journal")
        )
    }

    @Test
    fun chainedCreateAndDeleteIsMalformed() {
        val result = resolve("create journal Work and delete journal Travel")

        assertEquals(
            JournalManagementInvalidReason.MALFORMED,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun chainedCreateAndAddIsMalformed() {
        val result = resolve("create journal Work and add to Weight 72 kilograms")

        assertEquals(
            JournalManagementInvalidReason.MALFORMED,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun deletesExactExistingJournal() {
        val result = resolve("delete journal Travel Plans", listOf("Travel Plans"))

        assertEquals(
            JournalManagementResolution.Complete(
                JournalManagementCommand.Delete("Travel Plans")
            ),
            result
        )
    }

    @Test
    fun removesSuffixForm() {
        val result = resolve("remove the Travel Plans journal", listOf("Travel Plans"))

        assertEquals(
            JournalManagementResolution.Complete(
                JournalManagementCommand.Delete("Travel Plans")
            ),
            result
        )
    }

    @Test
    fun exactTitleWinsOverGeneratedAlias() {
        val result = resolve(
            "delete journal Weight Journal",
            listOf("Weight", "Weight Journal")
        )

        assertEquals(
            JournalManagementResolution.Complete(
                JournalManagementCommand.Delete("Weight Journal")
            ),
            result
        )
    }

    @Test
    fun duplicateRowsAreAmbiguous() {
        val result = resolve("delete journal Weight", listOf("Weight", "Weight"))

        assertEquals(
            JournalManagementInvalidReason.AMBIGUOUS,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun unknownDeleteDoesNotBecomeOrdinaryDictation() {
        val result = resolve("delete journal Does Not Exist", listOf("Weight"))

        assertEquals(
            JournalManagementInvalidReason.NOT_FOUND,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun incompleteDeleteDoesNotBecomeOrdinaryDictation() {
        assertEquals(
            JournalManagementResolution.Incomplete(JournalManagementOperation.DELETE),
            resolve("delete journal")
        )
    }

    @Test
    fun bulkDeleteIsRejected() {
        val result = resolve("delete all journals", listOf("Weight", "Travel"))

        assertEquals(
            JournalManagementInvalidReason.UNSAFE_TARGET,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun naturalBulkDeleteIsRejected() {
        val result = resolve("delete all of my journals", listOf("Weight", "Travel"))

        assertEquals(
            JournalManagementInvalidReason.UNSAFE_TARGET,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun bareKnownTitleRequiresJournalWord() {
        val result = resolve("delete Weight", listOf("Weight"))

        assertEquals(
            JournalManagementInvalidReason.REQUIRE_JOURNAL_WORD,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun ordinaryDeleteSentenceIsNotACommand() {
        assertEquals(
            JournalManagementResolution.NotACommand,
            resolve("delete this entry", listOf("Weight"))
        )
    }

    @Test
    fun proseMentioningCreateIsNotACommand() {
        assertEquals(
            JournalManagementResolution.NotACommand,
            resolve("I should create a journal someday", listOf("Weight"))
        )
    }

    @Test
    fun fullDeleteTargetMustMatch() {
        val result = resolve("delete journal Weight tomorrow", listOf("Weight"))

        assertEquals(
            JournalManagementInvalidReason.NOT_FOUND,
            (result as JournalManagementResolution.Invalid).reason
        )
    }

    @Test
    fun recognitionMayOmitTerminalPunctuationFromStoredTitle() {
        val result = resolve("delete journal Why", listOf("Why?"))

        assertEquals(
            JournalManagementResolution.Complete(
                JournalManagementCommand.Delete("Why?")
            ),
            result
        )
    }

    private fun resolve(
        spoken: String,
        known: Collection<String> = emptyList()
    ): JournalManagementResolution = JournalManagementVoiceParser.resolve(spoken, known)
}
