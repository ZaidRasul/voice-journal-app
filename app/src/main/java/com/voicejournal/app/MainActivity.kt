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
import com.voicejournal.app.data.BlockStyle
import com.voicejournal.app.data.BlockType
import com.voicejournal.app.data.JournalNote
import com.voicejournal.app.data.NoteBlock
import com.voicejournal.app.data.NoteStore
import com.voicejournal.app.data.preview
import com.voicejournal.app.voice.VoiceCommandParser
import com.voicejournal.app.voice.VoiceInputController
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var noteStore: NoteStore
    private lateinit var voiceInput: VoiceInputController

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosaveTask: Runnable? = null

    private var screen = Screen.NOTES
    private var activeDraft: JournalNote? = null
    private var noteList: LinearLayout? = null
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

        showNotesScreen(stopVoice = false)
    }

    override fun onStop() {
        if (::voiceInput.isInitialized && voiceInput.isRunning) {
            voiceInput.stop()
            activeVoiceTarget = null
        }
        saveCurrentNote(showConfirmation = false)
        super.onStop()
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

    private fun showNotesScreen(stopVoice: Boolean = true) {
        if (stopVoice) {
            stopVoiceInput()
        }
        saveCurrentNote(showConfirmation = false)
        activeDraft = null
        screen = Screen.NOTES
        blockList = null
        titleInput = null
        root.removeAllViews()

        val content = verticalLayout().apply {
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        val header = horizontalLayout(Gravity.CENTER_VERTICAL)
        val heading = verticalLayout()
        heading.addChild(
            label("Voice Journal", 28f, ink, Typeface.BOLD),
            width = 0,
            weight = 1f
        )
        heading.addChild(
            label("Notes that stay simple, even when your thoughts are not.", 14f, muted),
            width = 0,
            weight = 1f,
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
                "Dictate a command such as “add to Groceries: checkbox milk”.",
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

        val listHeader = horizontalLayout(Gravity.CENTER_VERTICAL)
        listHeader.addChild(
            label("Your notes", 18f, ink, Typeface.BOLD),
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
            roundedButton("+  New note", accent, Color.WHITE) { showEditorScreen(newNote()) },
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
        val notes = noteStore.listNotes()
        if (notes.isEmpty()) {
            val empty = verticalLayout().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(32), dp(24), dp(32))
                background = roundedBackground(surface, line, dp(18))
            }
            empty.addChild(label("No notes yet", 20f, ink, Typeface.BOLD))
            empty.addChild(
                label("Create one for ideas, groceries, plans, or anything else.", 14f, muted),
                top = dp(8)
            )
            container.addChild(empty)
        } else {
            notes.forEach { note ->
                container.addChild(noteCard(note), bottom = dp(10))
            }
        }
    }

    private fun noteCard(note: JournalNote): View {
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
            roundedButton("Delete", Color.TRANSPARENT, danger) { confirmDeleteFromList(note) },
            left = dp(8)
        )
        card.addChild(top)
        card.addChild(label(note.preview(), 14f, muted), top = dp(8))
        card.addChild(
            label("Updated " + formatUpdatedAt(note.updatedAt), 12f, muted),
            top = dp(10)
        )
        return card
    }

    private fun confirmDeleteFromList(note: JournalNote) {
        AlertDialog.Builder(this)
            .setTitle("Delete note?")
            .setMessage("“" + note.title + "” will be removed from this phone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                noteStore.delete(note.id)
                refreshNoteList()
                toast("Note deleted")
            }
            .show()
    }

    private fun showEditorScreen(note: JournalNote) {
        stopVoiceInput()
        screen = Screen.EDITOR
        activeDraft = note
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
            label(if (note.id == 0L) "New note" else "Edit note", 20f, ink, Typeface.BOLD),
            width = 0,
            weight = 1f,
            left = dp(12)
        )
        header.addChild(
            roundedButton("Save", accent, Color.WHITE) { saveCurrentNote(showConfirmation = true) }
        )
        content.addChild(header)

        titleInput = EditText(this).apply {
            hint = "Give this note a title"
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

        voiceStatus = label("Tap Speak to this note to dictate directly into it.", 13f, accent).also {
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
            roundedButton("🎙  Speak to this note", accent, Color.WHITE) {
                beginVoice(VoiceTarget.ACTIVE_NOTE)
            },
            width = 0,
            weight = 1f
        )
        actions.addChild(
            roundedButton("Delete", Color.TRANSPARENT, danger) { confirmDeleteCurrentNote() },
            left = dp(8)
        )
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

    private fun saveCurrentNote(showConfirmation: Boolean) {
        val draft = activeDraft ?: return
        noteStore.save(draft)
        val input = titleInput
        if (input != null && input.text.toString() != draft.title) {
            input.setText(draft.title)
            input.setSelection(draft.title.length)
        }
        if (showConfirmation) {
            toast("Note saved")
        }
    }

    private fun scheduleAutosave() {
        autosaveTask?.let(autosaveHandler::removeCallbacks)
        autosaveTask = Runnable { saveCurrentNote(showConfirmation = false) }
        autosaveHandler.postDelayed(autosaveTask!!, AUTOSAVE_DELAY_MS)
    }

    private fun confirmDeleteCurrentNote() {
        val draft = activeDraft ?: return
        if (draft.id == 0L) {
            navigateBack()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete note?")
            .setMessage("“" + draft.title + "” will be removed from this phone.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Delete") { _, _ ->
                noteStore.delete(draft.id)
                activeDraft = null
                showNotesScreen(stopVoice = false)
                toast("Note deleted")
            }
            .show()
    }

    private fun showRantScreen() {
        stopVoiceInput()
        saveCurrentNote(showConfirmation = false)
        activeDraft = null
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
            VoiceTarget.HOME_COMMAND -> addVoiceCommandToNote(transcript)
            VoiceTarget.ACTIVE_NOTE -> appendToActiveNote(transcript)
            VoiceTarget.RANT -> appendToRant(transcript)
            null -> Unit
        }

        if (activeVoiceTarget != VoiceTarget.RANT) {
            activeVoiceTarget = null
        }
    }

    private fun addVoiceCommandToNote(transcript: String) {
        val command = VoiceCommandParser.parse(transcript)
        if (command == null) {
            showVoiceStatus("I heard: “" + transcript + "”")
            toast("Say “add to Note title: text to add”.")
            return
        }

        val existing = noteStore.findMostRecentByTitle(command.noteTitle)
        val target = existing ?: JournalNote(title = command.noteTitle)
        target.blocks += command.block
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
        val lastBlock = draft.blocks.lastOrNull()
        if (
            lastBlock != null &&
            lastBlock.type == BlockType.TEXT &&
            lastBlock.style == BlockStyle.BODY &&
            lastBlock.text.isNotBlank()
        ) {
            lastBlock.text = lastBlock.text.trimEnd() + " " + transcript
        } else {
            draft.blocks += NoteBlock(type = BlockType.TEXT, text = transcript)
        }
        renderEditorBlocks()
        saveCurrentNote(showConfirmation = false)
        showVoiceStatus("Added to this note.")
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
            Screen.EDITOR, Screen.RANT -> showNotesScreen()
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
        RANT
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
    }
}
