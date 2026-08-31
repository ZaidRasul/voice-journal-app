package com.voicejournal.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class BlockType(val label: String) {
    TEXT("Text"),
    BULLET("Bulleted list"),
    NUMBERED("Numbered list"),
    CHECKBOX("Checklist")
}

enum class BlockStyle(val label: String) {
    BODY("Body"),
    HEADING("Heading"),
    SUBHEADING("Subheading"),
    QUOTE("Quote")
}

data class NoteBlock(
    val id: String = UUID.randomUUID().toString(),
    var type: BlockType = BlockType.TEXT,
    var text: String = "",
    var isChecked: Boolean = false,
    var style: BlockStyle = BlockStyle.BODY,
    val createdAt: Long = System.currentTimeMillis()
)

data class JournalNote(
    var id: Long = 0L,
    var title: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt,
    val blocks: MutableList<NoteBlock> = mutableListOf(NoteBlock(createdAt = createdAt))
)

/**
 * A journal-level search result. When the title matches, [entries] contains
 * every entry in that journal; otherwise it contains only matching entries.
 */
data class JournalSearchResult(
    val journal: JournalNote,
    val entries: List<NoteBlock>,
    val matchedJournalTitle: Boolean
)

/** Pure search logic kept separate from Android storage so it is JVM-testable. */
object JournalSearch {
    fun filter(journals: List<JournalNote>, query: String): List<JournalSearchResult> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()

        return journals.mapNotNull { journal ->
            val titleMatches = journal.title.contains(term, ignoreCase = true)
            val matchingEntries = journal.blocks.filter { entry ->
                entry.text.contains(term, ignoreCase = true)
            }

            when {
                titleMatches -> JournalSearchResult(
                    journal = journal,
                    entries = journal.blocks.toList(),
                    matchedJournalTitle = true
                )

                matchingEntries.isNotEmpty() -> JournalSearchResult(
                    journal = journal,
                    entries = matchingEntries,
                    matchedJournalTitle = false
                )

                else -> null
            }
        }
    }
}

/**
 * The ordered blocks live in one JSON column. This keeps the database tiny
 * while preserving mixed note content without an object-mapping library.
 */
object NoteCodec {
    fun encode(blocks: List<NoteBlock>): String {
        val array = JSONArray()
        blocks.forEach { block ->
            array.put(
                JSONObject()
                    .put("id", block.id)
                    .put("type", block.type.name)
                    .put("text", block.text)
                    .put("checked", block.isChecked)
                    .put("style", block.style.name)
                    .put("createdAt", block.createdAt)
            )
        }
        return array.toString()
    }

    fun decode(
        raw: String,
        fallbackCreatedAt: Long = System.currentTimeMillis()
    ): MutableList<NoteBlock> {
        val blocks = mutableListOf<NoteBlock>()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val blockId = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                blocks += NoteBlock(
                    id = blockId,
                    type = item.enumValue("type", BlockType.TEXT),
                    text = item.optString("text"),
                    isChecked = item.optBoolean("checked", false),
                    style = item.enumValue("style", BlockStyle.BODY),
                    createdAt = item.optLong("createdAt", fallbackCreatedAt)
                )
            }
        }
        if (blocks.isEmpty()) {
            blocks += NoteBlock(createdAt = fallbackCreatedAt)
        }
        return blocks
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumValue(
        key: String,
        fallback: T
    ): T = runCatching { enumValueOf<T>(optString(key)) }.getOrDefault(fallback)
}

fun JournalNote.preview(): String {
    val text = blocks.mapNotNull { block ->
        val value = block.text.trim()
        if (value.isBlank()) {
            null
        } else {
            when (block.type) {
                BlockType.TEXT -> value
                BlockType.BULLET -> "• " + value
                BlockType.NUMBERED -> "1. " + value
                BlockType.CHECKBOX -> if (block.isChecked) "☑ " + value else "☐ " + value
            }
        }
    }.joinToString("  ")

    return text.take(180).ifBlank { "Empty note" }
}
