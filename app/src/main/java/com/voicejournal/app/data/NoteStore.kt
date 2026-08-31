package com.voicejournal.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoteStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_NOTES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_CONTENT TEXT NOT NULL,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 has no migrations. Future changes must preserve local notes.
    }

    fun listNotes(): List<JournalNote> =
        readableDatabase.query(
            TABLE_NOTES,
            NOTE_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_UPDATED_AT DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toNote())
                }
            }
        }

    fun findById(id: Long): JournalNote? =
        readableDatabase.query(
            TABLE_NOTES,
            NOTE_COLUMNS,
            "$COLUMN_ID = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toNote() else null
        }

    fun findMostRecentByTitle(title: String): JournalNote? =
        readableDatabase.query(
            TABLE_NOTES,
            NOTE_COLUMNS,
            "$COLUMN_TITLE = ? COLLATE NOCASE",
            arrayOf(title.trim()),
            null,
            null,
            "$COLUMN_UPDATED_AT DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toNote() else null
        }

    /** Returns the reserved default journal without creating it. */
    fun findDefaultJournal(): JournalNote? = findMostRecentByTitle(DEFAULT_JOURNAL_TITLE)

    /**
     * Returns the default journal, creating it on first use. Existing databases
     * need no schema migration because journals are stored in the notes table.
     */
    fun ensureDefaultJournal(): JournalNote =
        findDefaultJournal() ?: JournalNote(title = DEFAULT_JOURNAL_TITLE).also(::save)

    /** Searches journal titles and entry text without case sensitivity. */
    fun search(query: String): List<JournalSearchResult> =
        JournalSearch.filter(listNotes(), query)

    fun save(note: JournalNote) {
        note.title = note.title.trim().ifBlank { "Untitled note" }
        if (note.blocks.isEmpty()) {
            note.blocks += NoteBlock()
        }
        note.updatedAt = System.currentTimeMillis()

        val values = ContentValues().apply {
            put(COLUMN_TITLE, note.title)
            put(COLUMN_CONTENT, NoteCodec.encode(note.blocks))
            put(COLUMN_CREATED_AT, note.createdAt)
            put(COLUMN_UPDATED_AT, note.updatedAt)
        }

        if (note.id == 0L) {
            note.id = writableDatabase.insertOrThrow(TABLE_NOTES, null, values)
        } else {
            writableDatabase.update(
                TABLE_NOTES,
                values,
                "$COLUMN_ID = ?",
                arrayOf(note.id.toString())
            )
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete(TABLE_NOTES, "$COLUMN_ID = ?", arrayOf(id.toString()))
    }

    private fun Cursor.toNote(): JournalNote {
        val createdAt = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT))
        return JournalNote(
            id = getLong(getColumnIndexOrThrow(COLUMN_ID)),
            title = getString(getColumnIndexOrThrow(COLUMN_TITLE)),
            createdAt = createdAt,
            updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
            blocks = NoteCodec.decode(
                raw = getString(getColumnIndexOrThrow(COLUMN_CONTENT)),
                fallbackCreatedAt = createdAt
            )
        )
    }

    companion object {
        const val DEFAULT_JOURNAL_TITLE = "Default Journal"

        private const val DATABASE_NAME = "voice_journal.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NOTES = "notes"
        private const val COLUMN_ID = "id"
        private const val COLUMN_TITLE = "title"
        private const val COLUMN_CONTENT = "content"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_UPDATED_AT = "updated_at"

        private val NOTE_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_TITLE,
            COLUMN_CONTENT,
            COLUMN_CREATED_AT,
            COLUMN_UPDATED_AT
        )
    }
}
