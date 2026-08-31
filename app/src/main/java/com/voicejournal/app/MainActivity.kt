package com.voicejournal.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.voicejournal.app.analytics.JournalAnalytics
import com.voicejournal.app.data.BlockStyle
import com.voicejournal.app.data.BlockType
import com.voicejournal.app.data.JournalNote
import com.voicejournal.app.data.NoteBlock
import com.voicejournal.app.data.NoteStore
import com.voicejournal.app.data.preview
import com.voicejournal.app.ui.TimeSeriesPoint
import com.voicejournal.app.ui.TrendLineChartView
import com.voicejournal.app.voice.VoiceCommandParser
import com.voicejournal.app.voice.VoiceInputController
import com.voicejournal.app.voice.VoiceQuery
import com.voicejournal.app.voice.VoiceQueryParser
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var noteStore: NoteStore
    private lateinit var voiceInput: VoiceInputController

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveTask: Runnable? = null
    private var draftDirty = false

    private var screen = Screen.NOTES
    private var activeDraft: JournalNote? = null
    private var activeAnalyticsJournalId: Long? = null
    private var noteList: LinearLayout? = null
    private var journalSearchInput: EditText? = null
    private var blockList: LinearLayout? = null
    private var titleInput: EditText? = null
    private var voiceStatus: TextView? = null
    private var voicePartial: TextView? = null
    private var rantTranscriptView: TextView? = null
    private var rantScrollView: ScrollView? = null

    private var pendingVoiceTarget: VoiceTarget? = null
    private var activeVoiceTarget: VoiceTarget? = null
    private var rantTranscript = ""

    private val paper = Color.rgb(248, 247, 242)
    private val surface = Color.WHITE
    private val ink = Color.rgb(23, 33, 43)
    private val muted = Color.rgb(100, 112, 125)
    private val line = Color.rgb(225, 228, 232)
    private val accent = Color.rgb(23, 107, 91)
    private val accentSoft = Color.rgb(223, 242, 236)
    private val danger = Color.rgb(179, 38, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        noteStore = NoteStore(applicationContext)
        noteStore.ensureDefaultJournal()
        rantTranscript = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getString(PREFERENCE_RANT_DRAFT, "")
            .orEmpty()

        root = FrameLayout(this).apply {
            setBackgroundColor(paper)
        }
        configureSystemBars()
        setContentView(root)

        voiceInput = VoiceInputController(
            context = applicationContext,
            onStatus = ::showVoiceStatus,
            onPartial = ::showVoicePartial,
            onFinal = ::handleFinalVoiceText,
            onFailure = ::showVoiceFailure
        )

        configureBackNavigation()
        restoreScreen(savedInstanceState)
    }

    override fun onStop() {
        if (::voiceInput.isInitialized && voiceInput.isRunning) {
            voiceInput.stop()
            activeVoiceTarget = null
        }
        saveCurrentNote(showConfirmation = false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveCurrentNote(showConfirmation = false)
        outState.putString(STATE_SCREEN, screen.name)
        outState.putLong(STATE_ACTIVE_JOURNAL_ID, activeDraft?.id ?: activeAnalyticsJournalId ?: 0L)
        outState.putString(STATE_SEARCH_QUERY, journalSearchInput?.text?.toString().orEmpty())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        autosaveTask?.let(autosaveHandler::removeCallbacks)
        if (::voiceInput.isInitialized) {
            voiceInput.destroy()
        }
        noteStore.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return
        }

        val requestedTarget = pendingVoiceTarget
        pendingVoiceTarget = null
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted && requestedTarget != null) {
            startVoice(requestedTarget)
        } else {
            showVoiceFailure("Microphone permission was not granted.")
        }
    }

    private fun configureSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun configureBackNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (screen == Screen.NOTES) finish() else navigateBack()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen == Screen.NOTES) {
            super.onBackPressed()
        } else {
            navigateBack()
        }
    }

    private fun restoreScreen(savedInstanceState: Bundle?) {
        val restoredScreen = savedInstanceState
            ?.getString(STATE_SCREEN)
            ?.let { savedName -> Screen.entries.firstOrNull { it.name == savedName } }
            ?: Screen.NOTES
        val journalId = savedInstanceState?.getLong(STATE_ACTIVE_JOURNAL_ID) ?: 0L
        when (restoredScreen) {
            Screen.EDITOR -> {
                val journal = journalId.takeIf { it != 0L }?.let(noteStore::findById) ?: newNote()
                showEditorScreen(journal)
            }

            Screen.RANT -> showRantScreen()
            Screen.ANALYTICS -> {
                val journal = journalId.takeIf { it != 0L }?.let(noteStore::findById)
                if (journal == null) showNotesScreen(stopVoice = false) else showAnalyticsScreen(journal)
            }

            Screen.NOTES -> showNotesScreen(
                stopVoice = false,
                initialQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
            )
        }
    }

    private fun showNotesScreen(stopVoice: Boolean = true, initialQuery: String = "") {
        if (stopVoice) {
            stopVoiceInput()
        }
        saveCurrentNote(showConfirmation = false)
        cancelPendingAutosave()
        noteStore.ensureDefaultJournal()
        activeDraft = null
        activeAnalyticsJournalId = null
        draftDirty = false
        screen = Screen.NOTES
        blockList = null
        titleInput = null
        journalSearchInput = null
        root.removeAllViews()

        val content = verticalLayout().apply {
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        val header = horizontalLayout(Gravity.CENTER_VERTICAL)
        val heading = verticalLayout()
        heading.addChild(
            label("Voice Journal", 28f, ink, Typeface.BOLD)
        )
        heading.addChild(
            label("Notes that stay simple, even when your thoughts are not.", 14f, muted),
            top = dp(2)
        )
        header.addChild(heading, width = 0, weight = 1f)
        header.addChild(
            roundedButton("Rant", accentSoft, accent) { showRantScreen() },
            left = dp(8)
        )
        content.addChild(header)

        content.addChild(
            label(
                "Add to a named journal, speak directly to Default Journal, or ask for entries and trends.",
                14f,
                muted
            ),
            top = dp(16)
        )

        voiceStatus = label("Ready for a voice command.", 13f, accent).also {
            it.setPadding(dp(12), dp(10), dp(12), dp(10))
            it.background = roundedBackground(accentSoft, cornerRadius = dp(12))
        }
        content.addChild(voiceStatus!!, top = dp(10))

        voicePartial = label("", 14f, muted).also {
            it.visibility = View.GONE
            it.setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        content.addChild(voicePartial!!, top = dp(2))

        val voiceCommandButton = roundedButton("🎙  Voice command", accent, Color.WHITE) {
            beginVoice(VoiceTarget.HOME_COMMAND)
        }
        content.addChild(voiceCommandButton, top = dp(10))

        journalSearchInput = EditText(this).apply {
            hint = "Search journals and entries"
            setText(initialQuery)
            setTextColor(ink)
            setHintTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(surface, line, dp(14))
            addTextChangedListener(afterTextChanged { refreshNoteList() })
        }
        content.addChild(journalSearchInput!!, top = dp(14))

        val listHeader = horizontalLayout(Gravity.CENTER_VERTICAL)
        listHeader.addChild(
            label("Your journals", 18f, ink, Typeface.BOLD),
            width = 0,
            weight = 1f
        )
        listHeader.addChild(label("Newest first", 12f, muted))
        content.addChild(listHeader, top = dp(20), bottom = dp(8))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        noteList = verticalLayout()
        scrollView.addView(
            noteList,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addChild(
            scrollView,
            width = ViewGroup.LayoutParams.MATCH_PARENT,
            height = 0,
            weight = 1f
        )

        content.addChild(
            roundedButton("+  New journal", accent, Color.WHITE) { showEditorScreen(newNote()) },
            top = dp(14)
        )

        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        refreshNoteList()
    }

    private fun refreshNoteList() {
        val container = noteList ?: return
        container.removeAllViews()
        val query = journalSearchInput?.text?.toString()?.trim().orEmpty()
        val notes = if (query.isBlank()) {
            noteStore.listNotes()
        } else {
            val matchingIds = noteStore.search(query).mapTo(mutableSetOf()) { it.journal.id }
            noteStore.listNotes().filter { it.id in matchingIds }
        }
        if (notes.isEmpty()) {
            val empty = verticalLayout().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(32), dp(24), dp(32))
                background = roundedBackground(surface, line, dp(18))
            }
            empty.addChild(
                label(
                    if (query.isBlank()) "No journals yet" else "No matching entries",
                    20f,
                    ink,
                    Typeface.BOLD
                )
            )
            empty.addChild(
                label(
                    if (query.isBlank()) {
                        "Create one for ideas, weight, plans, or anything else."
                    } else {
                        "Try a journal name or words from an entry."
                    },
                    14f,
                    muted
                ),
                top = dp(8)
            )
            container.addChild(empty)
        } else {
            notes.forEach { note ->
                container.addChild(noteCard(note, query), bottom = dp(10))
            }
        }
    }

    private fun noteCard(note: JournalNote, query: String): View {
        val card = verticalLayout().apply {
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = roundedBackground(surface, line, dp(18))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val freshNote = noteStore.findById(note.id) ?: note
                showEditorScreen(freshNote)
            }
        }

        val top = horizontalLayout(Gravity.CENTER_VERTICAL)
        top.addChild(
            label(note.title, 18f, ink, Typeface.BOLD),
            width = 0,
            weight = 1f
        )
        top.addChild(
            roundedButton("Trend", accentSoft, accent) {
                val freshJournal = noteStore.findById(note.id) ?: note
                showAnalyticsScreen(freshJournal)
            },
            left = dp(8)
        )
        if (!note.title.equals(NoteStore.DEFAULT_JOURNAL_TITLE, ignoreCase = true)) {
            top.addChild(
                roundedButton("Delete", Color.TRANSPARENT, danger) { confirmDeleteFromList(note) },
                left = dp(4)
            )
        }
        card.addChild(top)
        card.addChild(label(previewForSearch(note, query), 14f, muted), top = dp(8))
        card.addChild(
            label("Updated " + formatUpdatedAt(note.updatedAt), 12f, muted),
            top = dp(10)
        )
        return card
    }

    private fun previewForSearch(note: JournalNote, query: String): String {
        if (query.isBlank() || note.title.contains(query, ignoreCase = true)) {
            return note.preview()
        }
        return note.blocks
            .asSequence()
            .filter { it.text.contains(query, ignoreCase = true) }
            .take(2)
            .joinToString("  ") { block ->
                formatEntryTimestamp(block.createdAt) + " · " + block.text.trim()
            }
            .take(220)
            .ifBlank { note.preview() }
    }

    private fun confirmDeleteFromList(note: JournalNote) {
        AlertDialog.Builder(this)
            .setTitle("Delete journal?")
            .setMessage("“" + note.title + "” and all its entries will be removed from this phone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                noteStore.delete(note.id)
                refreshNoteList()
                toast("Journal deleted")
            }
            .show()
    }

    private fun showEditorScreen(note: JournalNote) {
        stopVoiceInput()
        cancelPendingAutosave()
        screen = Screen.EDITOR
        activeDraft = note
        activeAnalyticsJournalId = null
        draftDirty = false
        journalSearchInput = null
        root.removeAllViews()

        val content = verticalLayout().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val header = horizontalLayout(Gravity.CENTER_VERTICAL)
        header.addChild(
            roundedButton("←", accentSoft, accent) { navigateBack() },
            width = dp(48),
            height = dp(44)
        )
        header.addChild(
            label(if (note.id == 0L) "New journal" else "Edit journal", 20f, ink, Typeface.BOLD),
            width = 0,
            weight = 1f,
            left = dp(12)
        )
        header.addChild(
            roundedButton("Save", accent, Color.WHITE) {
                saveCurrentNote(showConfirmation = true, force = true)
            }
        )
        content.addChild(header)

        titleInput = EditText(this).apply {
            hint = "Give this journal a name"
            setText(note.title)
            setTextColor(ink)
            setHintTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(4), dp(12), dp(4), dp(10))
            background = roundedBackground(Color.TRANSPARENT)
            addTextChangedListener(afterTextChanged {
                activeDraft?.title = it
                scheduleAutosave()
            })
        }
        content.addChild(titleInput!!)

        voiceStatus = label("Tap Speak to this journal to add a timestamped entry.", 13f, accent).also {
            it.setPadding(dp(12), dp(9), dp(12), dp(9))
            it.background = roundedBackground(accentSoft, cornerRadius = dp(12))
        }
        content.addChild(voiceStatus!!, top = dp(4))

        voicePartial = label("", 14f, muted).also {
            it.visibility = View.GONE
            it.setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        content.addChild(voicePartial!!)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        blockList = verticalLayout()
        scrollView.addView(
            blockList,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addChild(
            scrollView,
            width = ViewGroup.LayoutParams.MATCH_PARENT,
            height = 0,
            weight = 1f,
            top = dp(8)
        )

        val typeChooser = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chooserContent = horizontalLayout(Gravity.CENTER_VERTICAL)
        chooserContent.addChild(label("Add:", 14f, muted), right = dp(8))
        chooserContent.addChild(
            roundedButton("Text", surface, accent) { addBlock(BlockType.TEXT) },
            right = dp(6)
        )
        chooserContent.addChild(
            roundedButton("• List", surface, accent) { addBlock(BlockType.BULLET) },
            right = dp(6)
        )
        chooserContent.addChild(
            roundedButton("1. List", surface, accent) { addBlock(BlockType.NUMBERED) },
            right = dp(6)
        )
        chooserContent.addChild(
            roundedButton("☐ Check", surface, accent) { addBlock(BlockType.CHECKBOX) }
        )
        typeChooser.addView(
            chooserContent,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addChild(typeChooser, top = dp(10))

        val actions = horizontalLayout(Gravity.CENTER_VERTICAL)
        actions.addChild(
            roundedButton("🎙  Speak to this journal", accent, Color.WHITE) {
                beginVoice(VoiceTarget.ACTIVE_NOTE)
            },
            width = 0,
            weight = 1f
        )
        if (!note.title.equals(NoteStore.DEFAULT_JOURNAL_TITLE, ignoreCase = true)) {
            actions.addChild(
                roundedButton("Delete", Color.TRANSPARENT, danger) { confirmDeleteCurrentNote() },
                left = dp(8)
            )
        }
        content.addChild(actions, top = dp(10))

        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        renderEditorBlocks()
    }

    private fun renderEditorBlocks() {
        val draft = activeDraft ?: return
        val container = blockList ?: return
        container.removeAllViews()
        if (draft.blocks.isEmpty()) {
            draft.blocks += NoteBlock()
        }
        draft.blocks.forEachIndexed { index, block ->
            container.addChild(blockEditor(block, index), bottom = dp(8))
        }
    }

    private fun blockEditor(block: NoteBlock, index: Int): View {
        val card = verticalLayout().apply {
            setPadding(dp(10), dp(8), dp(8), dp(10))
            background = roundedBackground(surface, line, dp(14))
        }

        val toolbar = horizontalLayout(Gravity.CENTER_VERTICAL)
        toolbar.addChild(
            label(block.type.label, 12f, muted, Typeface.BOLD),
            width = 0,
            weight = 1f
        )

        val styleButton = roundedButton(block.style.label, accentSoft, accent) {
            showStyleMenu(it, block.id)
        }
        toolbar.addChild(styleButton)
        toolbar.addChild(
            roundedButton("↑", Color.TRANSPARENT, muted) { moveBlock(block.id, -1) },
            width = dp(34),
            height = dp(36),
            left = dp(2)
        )
        toolbar.addChild(
            roundedButton("↓", Color.TRANSPARENT, muted) { moveBlock(block.id, 1) },
            width = dp(34),
            height = dp(36)
        )
        toolbar.addChild(
            roundedButton("×", Color.TRANSPARENT, danger) { removeBlock(block.id) },
            width = dp(34),
            height = dp(36)
        )
        card.addChild(toolbar)
        card.addChild(
            label("Entry " + formatEntryTimestamp(block.createdAt), 11f, muted),
            top = dp(2)
        )

        val body = horizontalLayout(Gravity.TOP)
        if (block.type == BlockType.CHECKBOX) {
            val checkbox = CheckBox(this).apply {
                isChecked = block.isChecked
                contentDescription = "Mark checklist item complete"
                setOnCheckedChangeListener { _, checked ->
                    findBlock(block.id)?.isChecked = checked
                    scheduleAutosave()
                }
            }
            body.addChild(
                checkbox,
                width = dp(42),
                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                top = dp(5)
            )
        } else {
            body.addChild(
                label(markerFor(block, index), 16f, accent, Typeface.BOLD).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, dp(10), 0, 0)
                },
                width = dp(40)
            )
        }

        val textInput = EditText(this).apply {
            setText(block.text)
            setTextColor(ink)
            setHintTextColor(muted)
            hint = hintFor(block.type)
            minLines = if (block.style == BlockStyle.HEADING) 1 else 2
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(4), dp(4), dp(4), dp(6))
            background = roundedBackground(Color.TRANSPARENT)
            applyTextStyle(this, block.style)
            addTextChangedListener(afterTextChanged {
                findBlock(block.id)?.text = it
                scheduleAutosave()
            })
        }
        body.addChild(
            textInput,
            width = 0,
            height = ViewGroup.LayoutParams.WRAP_CONTENT,
            weight = 1f
        )
        card.addChild(body, top = dp(4))
        return card
    }

    private fun markerFor(block: NoteBlock, index: Int): String = when (block.type) {
        BlockType.TEXT -> "T"
        BlockType.BULLET -> "•"
        BlockType.NUMBERED -> {
            val ordinal = activeDraft?.blocks
                ?.take(index + 1)
                ?.count { it.type == BlockType.NUMBERED }
                ?: 1
            ordinal.toString() + "."
        }

        BlockType.CHECKBOX -> ""
    }

    private fun hintFor(type: BlockType): String = when (type) {
        BlockType.TEXT -> "Write something…"
        BlockType.BULLET -> "List item"
        BlockType.NUMBERED -> "Numbered step"
        BlockType.CHECKBOX -> "Task to check off"
    }

    private fun applyTextStyle(input: EditText, style: BlockStyle) {
        when (style) {
            BlockStyle.BODY -> {
                input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                input.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                input.setTextColor(ink)
            }

            BlockStyle.HEADING -> {
                input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                input.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                input.setTextColor(ink)
            }

            BlockStyle.SUBHEADING -> {
                input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                input.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                input.setTextColor(ink)
            }

            BlockStyle.QUOTE -> {
                input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                input.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC))
                input.setTextColor(muted)
            }
        }
    }

    private fun showStyleMenu(anchor: View, blockId: String) {
        val popup = PopupMenu(this, anchor)
        BlockStyle.entries.forEach { style ->
            popup.menu.add(style.label).setOnMenuItemClickListener {
                findBlock(blockId)?.style = style
                renderEditorBlocks()
                scheduleAutosave()
                true
            }
        }
        popup.show()
    }

    private fun addBlock(type: BlockType) {
        activeDraft?.blocks?.add(NoteBlock(type = type))
        renderEditorBlocks()
        scheduleAutosave()
    }

    private fun moveBlock(blockId: String, direction: Int) {
        val draft = activeDraft ?: return
        val currentIndex = draft.blocks.indexOfFirst { it.id == blockId }
        val newIndex = currentIndex + direction
        if (currentIndex < 0 || newIndex !in draft.blocks.indices) {
            return
        }
        val block = draft.blocks.removeAt(currentIndex)
        draft.blocks.add(newIndex, block)
        renderEditorBlocks()
        scheduleAutosave()
    }

    private fun removeBlock(blockId: String) {
        val draft = activeDraft ?: return
        val index = draft.blocks.indexOfFirst { it.id == blockId }
        if (index < 0) {
            return
        }
        if (draft.blocks.size == 1) {
            draft.blocks[0] = NoteBlock()
        } else {
            draft.blocks.removeAt(index)
        }
        renderEditorBlocks()
        scheduleAutosave()
    }

    private fun findBlock(blockId: String): NoteBlock? =
        activeDraft?.blocks?.firstOrNull { it.id == blockId }

    private fun saveCurrentNote(showConfirmation: Boolean, force: Boolean = false) {
        val draft = activeDraft ?: return
        if (!force && !draftDirty) {
            return
        }
        cancelPendingAutosave()
        noteStore.save(draft)
        val input = titleInput
        if (input != null && input.text.toString() != draft.title) {
            input.setText(draft.title)
            input.setSelection(draft.title.length)
        }
        cancelPendingAutosave()
        draftDirty = false
        if (showConfirmation) {
            toast("Journal saved")
        }
    }

    private fun scheduleAutosave() {
        draftDirty = true
        autosaveTask?.let(autosaveHandler::removeCallbacks)
        autosaveTask = Runnable {
            autosaveTask = null
            saveCurrentNote(showConfirmation = false)
        }
        autosaveHandler.postDelayed(autosaveTask!!, AUTOSAVE_DELAY_MS)
    }

    private fun cancelPendingAutosave() {
        autosaveTask?.let(autosaveHandler::removeCallbacks)
        autosaveTask = null
    }

    private fun confirmDeleteCurrentNote() {
        val draft = activeDraft ?: return
        if (draft.id == 0L) {
            cancelPendingAutosave()
            activeDraft = null
            draftDirty = false
            showNotesScreen(stopVoice = false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete journal?")
            .setMessage("“" + draft.title + "” and all its entries will be removed from this phone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                noteStore.delete(draft.id)
                activeDraft = null
                draftDirty = false
                showNotesScreen(stopVoice = false)
                toast("Journal deleted")
            }
            .show()
    }

    private fun showAnalyticsScreen(
        journal: JournalNote,
        since: Long = Long.MIN_VALUE,
        until: Long = Long.MAX_VALUE,
        rangeLabel: String = "All timestamped entries"
    ) {
        stopVoiceInput()
        saveCurrentNote(showConfirmation = false)
        cancelPendingAutosave()
        activeDraft = null
        activeAnalyticsJournalId = journal.id
        draftDirty = false
        journalSearchInput = null
        noteList = null
        blockList = null
        titleInput = null
        voiceStatus = null
        voicePartial = null
        screen = Screen.ANALYTICS
        root.removeAllViews()

        val summary = JournalAnalytics.analyze(journal.blocks, since, until)
        val content = verticalLayout().apply {
            setPadding(dp(16), dp(14), dp(16), dp(22))
        }
        val header = horizontalLayout(Gravity.CENTER_VERTICAL)
        header.addChild(
            roundedButton("←", accentSoft, accent) { navigateBack() },
            width = dp(48),
            height = dp(44)
        )
        header.addChild(
            label(journal.title + " trend", 22f, ink, Typeface.BOLD),
            width = 0,
            weight = 1f,
            left = dp(12)
        )
        content.addChild(header)
        content.addChild(label(rangeLabel, 13f, muted), top = dp(8))

        val chart = TrendLineChartView(this).apply {
            setChartTitle(journal.title)
            setPoints(
                summary?.points.orEmpty().map { point ->
                    TimeSeriesPoint(point.timestamp, point.value)
                }
            )
        }
        content.addChild(
            chart,
            height = dp(300),
            top = dp(14)
        )

        if (summary == null) {
            content.addChild(
                label(
                    "No numeric entries were found for this period. Add entries such as “72.4 kg” to graph a trend.",
                    14f,
                    muted
                ),
                top = dp(12)
            )
        } else {
            val direction = when {
                summary.change > 0.0 -> "+"
                else -> ""
            }
            val metrics = verticalLayout().apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBackground(surface, line, dp(14))
            }
            metrics.addChild(
                label(
                    "${summary.points.size} measurements · Change $direction${formatNumber(summary.change)}",
                    16f,
                    ink,
                    Typeface.BOLD
                )
            )
            metrics.addChild(
                label(
                    "Start ${formatNumber(summary.startValue)}  ·  Latest ${formatNumber(summary.endValue)}",
                    14f,
                    muted
                ),
                top = dp(6)
            )
            metrics.addChild(
                label(
                    "Minimum ${formatNumber(summary.min)}  ·  Maximum ${formatNumber(summary.max)}",
                    14f,
                    muted
                ),
                top = dp(4)
            )
            content.addChild(metrics, top = dp(12))

            content.addChild(label("Measurements", 17f, ink, Typeface.BOLD), top = dp(18))
            summary.points.asReversed().take(12).forEach { point ->
                val row = verticalLayout().apply {
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    background = roundedBackground(surface, line, dp(12))
                }
                row.addChild(
                    label(
                        formatNumber(point.value) + " · " + formatEntryTimestamp(point.timestamp),
                        14f,
                        ink,
                        Typeface.BOLD
                    )
                )
                row.addChild(label(point.sourceText, 13f, muted), top = dp(3))
                content.addChild(row, top = dp(7))
            }
        }

        content.addChild(
            roundedButton("Edit journal", accent, Color.WHITE) {
                val freshJournal = noteStore.findById(journal.id) ?: journal
                showEditorScreen(freshJournal)
            },
            top = dp(16)
        )

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            scrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showRantScreen() {
        stopVoiceInput()
        saveCurrentNote(showConfirmation = false)
        cancelPendingAutosave()
        activeDraft = null
        activeAnalyticsJournalId = null
        draftDirty = false
        journalSearchInput = null
        noteList = null
        blockList = null
        titleInput = null
        screen = Screen.RANT
        root.removeAllViews()

        val content = verticalLayout().apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val header = horizontalLayout(Gravity.CENTER_VERTICAL)
        header.addChild(
            roundedButton("←", accentSoft, accent) { navigateBack() },
            width = dp(48),
            height = dp(44)
        )
        header.addChild(
            label("Rant mode", 22f, ink, Typeface.BOLD),
            width = 0,
            weight = 1f,
            left = dp(12)
        )
        header.addChild(
            roundedButton("Clear", Color.TRANSPARENT, danger) { confirmClearRant() }
        )
        content.addChild(header)

        content.addChild(
            label(
                "This space just writes. It never runs voice commands. Stop when you need a breath.",
                14f,
                muted
            ),
            top = dp(12)
        )

        voiceStatus = label("Tap Start rant when you are ready.", 13f, accent).also {
            it.setPadding(dp(12), dp(9), dp(12), dp(9))
            it.background = roundedBackground(accentSoft, cornerRadius = dp(12))
        }
        content.addChild(voiceStatus!!, top = dp(12))

        voicePartial = label("", 14f, muted).also {
            it.visibility = View.GONE
            it.setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        content.addChild(voicePartial!!)

        rantScrollView = ScrollView(this).apply {
            isFillViewport = true
            setPadding(0, dp(8), 0, dp(8))
        }
        rantTranscriptView = label("", 17f, ink).apply {
            setTextIsSelectable(true)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = roundedBackground(surface, line, dp(18))
        }
        rantScrollView?.addView(
            rantTranscriptView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addChild(
            rantScrollView!!,
            width = ViewGroup.LayoutParams.MATCH_PARENT,
            height = 0,
            weight = 1f
        )

        val controls = horizontalLayout(Gravity.CENTER_VERTICAL)
        controls.addChild(
            roundedButton("▶  Start rant", accent, Color.WHITE) {
                beginVoice(VoiceTarget.RANT)
            },
            width = 0,
            weight = 1f
        )
        controls.addChild(
            roundedButton("■  Stop", accentSoft, accent) {
                stopVoiceInput()
            },
            left = dp(8)
        )
        content.addChild(controls, top = dp(8))
        content.addChild(
            roundedButton("Save transcript as note", surface, accent) { saveRantAsNote() },
            top = dp(8)
        )

        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        renderRantTranscript()
    }

    private fun renderRantTranscript() {
        val view = rantTranscriptView ?: return
        if (rantTranscript.isBlank()) {
            view.text = "Your spoken thoughts will appear here."
            view.setTextColor(muted)
            view.setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
        } else {
            view.text = rantTranscript
            view.setTextColor(ink)
            view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            rantScrollView?.post { rantScrollView?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun confirmClearRant() {
        if (rantTranscript.isBlank()) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Clear rant transcript?")
            .setMessage("This clears the unsaved rant text. Saved notes stay untouched.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Clear") { _, _ ->
                rantTranscript = ""
                getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .remove(PREFERENCE_RANT_DRAFT)
                    .apply()
                renderRantTranscript()
            }
            .show()
    }

    private fun saveRantAsNote() {
        if (rantTranscript.isBlank()) {
            toast("Start rant mode and speak first.")
            return
        }
        val title = "Rant " + DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date())
        val note = JournalNote(
            title = title,
            blocks = mutableListOf(NoteBlock(type = BlockType.TEXT, text = rantTranscript))
        )
        noteStore.save(note)
        toast("Transcript saved as a note")
    }

    private fun beginVoice(target: VoiceTarget) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingVoiceTarget = target
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        startVoice(target)
    }

    private fun startVoice(target: VoiceTarget) {
        activeVoiceTarget = target
        if (target == VoiceTarget.RANT) {
            voiceInput.startRant()
        } else {
            voiceInput.startSingle()
        }
    }

    private fun stopVoiceInput() {
        if (::voiceInput.isInitialized && voiceInput.isRunning) {
            voiceInput.stop()
        }
        activeVoiceTarget = null
        showVoicePartial("")
    }

    private fun handleFinalVoiceText(transcript: String) {
        when (activeVoiceTarget) {
            VoiceTarget.HOME_COMMAND -> handleHomeVoice(transcript)
            VoiceTarget.ACTIVE_NOTE -> appendToActiveNote(transcript)
            VoiceTarget.RANT -> appendToRant(transcript)
            null -> Unit
        }

        if (activeVoiceTarget != VoiceTarget.RANT) {
            activeVoiceTarget = null
        }
    }

    private fun handleHomeVoice(transcript: String) {
        when (val query = VoiceQueryParser.parse(transcript)) {
            is VoiceQuery.ShowEntries -> {
                val journal = findJournalBySpokenName(query.journalName)
                if (journal == null) {
                    journalSearchInput?.setText(query.journalName)
                    showVoiceStatus("No journal named “${query.journalName}” was found.")
                    toast("Journal not found")
                } else {
                    showEditorScreen(journal)
                    toast("Showing entries from “${journal.title}”")
                }
            }

            is VoiceQuery.AnalyzeTrend -> {
                val journal = findJournalBySpokenName(query.journalName)
                if (journal == null) {
                    journalSearchInput?.setText(query.journalName)
                    showVoiceStatus("No journal named “${query.journalName}” was found.")
                    toast("Journal not found")
                } else {
                    showAnalyticsScreen(
                        journal = journal,
                        since = query.range.startInclusiveMillis,
                        until = query.range.endInclusiveMillis,
                        rangeLabel = "Last ${VoiceQueryParser.LAST_MONTH_DAYS} days"
                    )
                }
            }

            null -> addVoiceCommandToNote(transcript)
        }
    }

    private fun addVoiceCommandToNote(transcript: String) {
        val command = VoiceCommandParser.parse(transcript)
        if (command == null) {
            val target = noteStore.ensureDefaultJournal()
            val entry = VoiceCommandParser.parseEntry(transcript)
            appendJournalEntry(target, entry)
            noteStore.save(target)
            refreshNoteList()
            showVoiceStatus("Added to “${target.title}”")
            toast("Voice entry saved")
            return
        }

        val existing = noteStore.findMostRecentByTitle(command.noteTitle)
        val target = existing ?: JournalNote(title = command.noteTitle)
        appendJournalEntry(target, command.block)
        noteStore.save(target)
        refreshNoteList()
        val outcome = if (existing == null) "Created " else "Added to "
        showVoiceStatus(outcome + "“" + target.title + "”")
        toast("Voice text saved")
    }

    private fun appendToActiveNote(transcript: String) {
        val draft = activeDraft
        if (draft == null || screen != Screen.EDITOR) {
            return
        }
        appendJournalEntry(draft, NoteBlock(type = BlockType.TEXT, text = transcript))
        renderEditorBlocks()
        draftDirty = true
        saveCurrentNote(showConfirmation = false)
        showVoiceStatus("Added a timestamped entry to this journal.")
    }

    private fun appendJournalEntry(journal: JournalNote, entry: NoteBlock) {
        if (journal.blocks.size == 1 && journal.blocks[0].text.isBlank()) {
            journal.blocks[0] = entry
        } else {
            journal.blocks += entry
        }
    }

    private fun findJournalBySpokenName(name: String): JournalNote? {
        val normalized = name.trim()
        noteStore.findMostRecentByTitle(normalized)?.let { return it }
        noteStore.findMostRecentByTitle("$normalized Journal")?.let { return it }
        return noteStore.listNotes().firstOrNull { journal ->
            journal.title.removeSuffix(" Journal").equals(normalized, ignoreCase = true)
        }
    }

    private fun appendToRant(transcript: String) {
        rantTranscript = if (rantTranscript.isBlank()) {
            transcript
        } else {
            rantTranscript.trimEnd() + "\n\n" + transcript
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_RANT_DRAFT, rantTranscript)
            .apply()
        renderRantTranscript()
    }

    private fun showVoiceStatus(message: String) {
        voiceStatus?.text = message
    }

    private fun showVoicePartial(partial: String) {
        val view = voicePartial ?: return
        view.text = if (partial.isBlank()) "" else "Listening: " + partial
        view.visibility = if (partial.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showVoiceFailure(message: String) {
        activeVoiceTarget = null
        showVoiceStatus(message)
        showVoicePartial("")
        toast(message)
    }

    private fun navigateBack() {
        when (screen) {
            Screen.NOTES -> finish()
            Screen.EDITOR, Screen.RANT, Screen.ANALYTICS -> showNotesScreen()
        }
    }

    private fun newNote(): JournalNote = JournalNote(
        blocks = mutableListOf(NoteBlock())
    )

    private fun formatUpdatedAt(time: Long): String =
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(time))

    private fun formatEntryTimestamp(time: Long): String =
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            Locale.getDefault()
        ).format(Date(time))

    private fun formatNumber(value: Double): String = NumberFormat.getNumberInstance().run {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
        format(value)
    }

    private fun verticalLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun horizontalLayout(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        style: Int = Typeface.NORMAL
    ): TextView = TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTypeface(Typeface.DEFAULT, style)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun roundedButton(
        text: String,
        fillColor: Int,
        textColor: Int,
        onClick: (View) -> Unit
    ): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        minimumHeight = dp(42)
        minHeight = dp(42)
        setPadding(dp(12), 0, dp(12), 0)
        background = roundedBackground(fillColor, if (fillColor == surface) line else null, dp(12))
        setOnClickListener(onClick)
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int? = null,
        cornerRadius: Int = dp(12)
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        this.cornerRadius = cornerRadius.toFloat()
        if (strokeColor != null) {
            setStroke(dp(1), strokeColor)
        }
    }

    private fun LinearLayout.addChild(
        view: View,
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        weight: Float = 0f,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0
    ) {
        val params = LinearLayout.LayoutParams(width, height, weight).apply {
            setMargins(left, top, right, bottom)
        }
        addView(view, params)
    }

    private fun afterTextChanged(action: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                action(editable?.toString().orEmpty())
            }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private enum class Screen {
        NOTES,
        EDITOR,
        RANT,
        ANALYTICS
    }

    private enum class VoiceTarget {
        HOME_COMMAND,
        ACTIVE_NOTE,
        RANT
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 401
        const val AUTOSAVE_DELAY_MS = 650L
        const val PREFERENCES = "voice_journal_preferences"
        const val PREFERENCE_RANT_DRAFT = "rant_draft"
        const val STATE_SCREEN = "screen"
        const val STATE_ACTIVE_JOURNAL_ID = "active_journal_id"
        const val STATE_SEARCH_QUERY = "search_query"
    }
}
