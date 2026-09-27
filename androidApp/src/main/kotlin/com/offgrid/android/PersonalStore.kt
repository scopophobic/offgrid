package com.offgrid.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.offgrid.shared.models.AnswerSource
import com.offgrid.shared.models.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedChat(val id: String, val title: String, val updatedAt: Long)
data class LibraryItem(val id: String, val title: String, val collection: String, val text: String, val location: String, val savedAt: Long)
data class PersonalPack(val name: String, val description: String, val itemCount: Int)

/** Private app database. All public methods are called on Dispatchers.IO. */
class PersonalStore(context: Context) : SQLiteOpenHelper(context, "personal.db", null, 3) {
    private val deletedChats = mutableSetOf<String>()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats(id TEXT PRIMARY KEY, title TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id TEXT PRIMARY KEY, chat TEXT NOT NULL, position INTEGER, body TEXT, user INTEGER, sources TEXT, interrupted INTEGER, task TEXT)")
        db.execSQL("CREATE INDEX messages_chat ON messages(chat,position)")
        db.execSQL("CREATE TABLE library(id TEXT PRIMARY KEY, title TEXT, collection TEXT, body TEXT, location TEXT, saved INTEGER)")
        db.execSQL("CREATE TABLE personal_packs(name TEXT PRIMARY KEY, description TEXT NOT NULL)")
        db.execSQL("CREATE VIRTUAL TABLE passages USING fts4(item, title, body, location, saved)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN task TEXT")
        if (oldVersion < 3) db.execSQL("CREATE TABLE personal_packs(name TEXT PRIMARY KEY, description TEXT NOT NULL)")
    }

    @Synchronized fun saveChat(id: String, messages: List<ChatMessage>) {
        if (messages.isEmpty() || id in deletedChats) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("chats", null, ContentValues().apply {
                put("id", id); put("title", messages.first().text.take(70)); put("updated", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("chats", ContentValues().apply { put("updated", System.currentTimeMillis()) }, "id=?", arrayOf(id))
            db.delete("messages", "chat=?", arrayOf(id))
            messages.forEachIndexed { index, message ->
                db.insertOrThrow("messages", null, ContentValues().apply {
                    put("id", message.id); put("chat", id); put("position", index); put("body", message.text)
                    put("user", if (message.fromUser) 1 else 0); put("interrupted", if (message.interrupted) 1 else 0)
                    put("task", message.taskId)
                    put("sources", JSONArray().apply { message.sources.forEach { s -> put(JSONObject().apply {
                        put("title", s.title); put("passage", s.passage); put("location", s.location); put("saved", s.savedAt)
                    }) } }.toString())
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun chats(query: String = ""): List<SavedChat> {
        val result = mutableListOf<SavedChat>()
        readableDatabase.rawQuery("SELECT id,title,updated FROM chats WHERE title LIKE ? OR id IN (SELECT chat FROM messages WHERE body LIKE ?) ORDER BY updated DESC", arrayOf("%$query%", "%$query%")).use { c ->
            while (c.moveToNext()) result += SavedChat(c.getString(0), c.getString(1), c.getLong(2))
        }
        return result
    }
    @Synchronized fun messages(id: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery("SELECT id,body,user,sources,interrupted,task FROM messages WHERE chat=? ORDER BY position", arrayOf(id)).use { c ->
            while (c.moveToNext()) {
                val a = JSONArray(c.getString(3))
                val sources = (0 until a.length()).map { i -> a.getJSONObject(i).let { s ->
                    AnswerSource(s.getString("title"), s.getString("passage"), s.optString("location"), s.optLong("saved"))
                } }
                result += ChatMessage(c.getString(0), c.getString(1), c.getInt(2) == 1, sources, c.getInt(4) == 1, c.getString(5))
            }
        }
        return result
    }
    @Synchronized fun renameChat(id: String, title: String) {
        require(title.isNotBlank()) { "Enter a chat title." }
        writableDatabase.update("chats", ContentValues().apply { put("title", title.trim().take(100)) }, "id=?", arrayOf(id))
    }
    @Synchronized fun deleteChat(id: String) {
        deletedChats += id
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", "chat=?", arrayOf(id))
            writableDatabase.delete("chats", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun saveItem(title: String, text: String, collection: String = "Personal", location: String = ""): LibraryItem {
        require(text.isNotBlank()) { "No readable text found. Scanned PDFs need OCR, which is not included." }
        require(text.length <= 500_000) { "Document is too large. Import a shorter document (500,000 characters maximum)." }
        val item = LibraryItem(UUID.randomUUID().toString(), title.ifBlank { "Saved note" }.take(160), collection.trim().ifBlank { "Personal" }.take(80), text, location, System.currentTimeMillis())
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("library", null, ContentValues().apply {
                put("id", item.id); put("title", item.title); put("collection", item.collection); put("body", text); put("location", location); put("saved", item.savedAt)
            })
            val pageMarkers = Regex("\\[Page (\\d+)]").findAll(text).map { it.range.first to it.groupValues[1] }.toList()
            var pageIndex = 0
            text.windowed(1200, 1000, partialWindows = true).forEachIndexed { index, chunk ->
                while (pageIndex + 1 < pageMarkers.size && pageMarkers[pageIndex + 1].first <= index * 1000) pageIndex++
                val page = pageMarkers.getOrNull(pageIndex)?.takeIf { it.first <= index * 1000 }?.second
                db.insertOrThrow("passages", null, ContentValues().apply {
                    put("item", item.id); put("title", item.title); put("body", chunk)
                    put("location", if (location.isNotBlank()) location else if(page != null) "Page $page" else "Passage ${index + 1}"); put("saved", item.savedAt)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return item
    }
    @Synchronized fun library(query: String = ""): List<LibraryItem> {
        val result = mutableListOf<LibraryItem>()
        readableDatabase.rawQuery("SELECT id,title,collection,body,location,saved FROM library WHERE title LIKE ? OR collection LIKE ? OR body LIKE ? ORDER BY saved DESC", arrayOf("%$query%", "%$query%", "%$query%")).use { c ->
            while(c.moveToNext()) result += LibraryItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))
        }
        return result
    }
    @Synchronized fun createPack(name: String, description: String) {
        val clean = name.trim().take(80)
        require(clean.isNotBlank()) { "Give the pack a name." }
        require(clean != "Personal" && clean != "Answers" && clean != "Sources") { "Choose another pack name." }
        writableDatabase.insertOrThrow("personal_packs", null, ContentValues().apply {
            put("name", clean); put("description", description.trim().take(300))
        })
    }
    @Synchronized fun packs(): List<PersonalPack> {
        val result = mutableListOf<PersonalPack>()
        readableDatabase.rawQuery("SELECT p.name,p.description,COUNT(l.id) FROM personal_packs p LEFT JOIN library l ON l.collection=p.name GROUP BY p.name ORDER BY p.name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) result += PersonalPack(c.getString(0), c.getString(1), c.getInt(2))
        }
        return result
    }
    @Synchronized fun moveItem(id: String, collection: String) {
        writableDatabase.update("library", ContentValues().apply { put("collection", collection.trim().ifBlank { "Personal" }.take(80)) }, "id=?", arrayOf(id))
    }
    @Synchronized fun deleteItem(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("passages", "item=?", arrayOf(id)); db.delete("library", "id=?", arrayOf(id)); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun search(query: String, collection: String = "", itemId: String? = null): List<AnswerSource> {
        val stop = setOf("the", "and", "for", "with", "from", "this", "that", "what", "where", "when", "which", "would", "could", "should", "summarize", "explain", "source", "document", "about", "please", "selected")
        val terms = Regex("[\\p{L}\\p{N}]{3,}").findAll(query.lowercase()).map { it.value }.filterNot { it in stop }.distinct().take(12).toList()
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (terms.isNotEmpty()) { where += "passages MATCH ?"; args += terms.joinToString(" OR ") { "\"$it\"" } }
        else if (itemId == null) return emptyList()
        if (collection.isNotBlank()) { where += "item IN (SELECT id FROM library WHERE collection=?)"; args += collection }
        if (itemId != null) { where += "item=?"; args += itemId }
        val found = mutableListOf<AnswerSource>()
        readableDatabase.rawQuery("SELECT title,body,location,saved FROM passages WHERE ${where.joinToString(" AND ")} LIMIT 30", args.toTypedArray()).use { c ->
            while(c.moveToNext()) found += AnswerSource(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        return found.sortedByDescending { s -> terms.sumOf { term -> Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE).findAll(s.passage).count() } }.take(3)
    }
    @Synchronized fun overview(itemId: String): List<AnswerSource> {
        val all = mutableListOf<AnswerSource>()
        readableDatabase.rawQuery("SELECT title,body,location,saved FROM passages WHERE item=? ORDER BY rowid", arrayOf(itemId)).use { c ->
            while (c.moveToNext()) all += AnswerSource(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        if (all.size <= 3) return all
        return listOf(all.first(), all[all.size / 2], all.last())
    }
}
