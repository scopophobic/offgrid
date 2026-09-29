package com.offgrid.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offgrid.shared.models.ChatMessage
import com.offgrid.shared.models.AnswerSource
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalStoreTest {
    // Isolated database directory: never clear the developer's actual library.
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = object : android.content.ContextWrapper(base) {
        override fun getDatabasePath(name: String) = java.io.File(base.cacheDir, "test-$name")
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
    }
    private lateinit var store: PersonalStore
    @Before fun setup() { android.database.sqlite.SQLiteDatabase.deleteDatabase(context.getDatabasePath("personal.db")); store = PersonalStore(context) }
    @After fun cleanup() { store.close(); android.database.sqlite.SQLiteDatabase.deleteDatabase(context.getDatabasePath("personal.db")) }
    @Test fun chatSurvivesReopenWithSourcesAndRename() {
        val messages = listOf(ChatMessage("u", "Travel question", true), ChatMessage("a", "Answer", false, listOf(AnswerSource("Guide", "Passage", "page 2", 123)), true))
        store.saveChat("chat", messages); store.renameChat("chat", "Japan")
        store.close(); store = PersonalStore(context)
        assertEquals(messages, store.messages("chat"))
        assertEquals("Japan", store.chats("Passage").firstOrNull()?.title ?: store.chats("Answer").single().title)
        store.deleteChat("chat"); store.saveChat("chat", messages)
        assertTrue(store.chats().isEmpty()); assertTrue(store.messages("chat").isEmpty())
    }
    @Test fun librarySearchRespectsCollectionsAndDeletion() {
        val a = store.saveItem("Japan guide", "Kyoto trains and temples", "Travel")
        store.saveItem("Work", "Kyoto business meeting", "Work")
        assertEquals("Japan guide", store.search("Kyoto", "Travel").single().title)
        store.moveItem(a.id, "Japan")
        assertTrue(store.search("Kyoto", "Travel").isEmpty())
        assertEquals(1, store.search("Kyoto", "Japan").size)
        store.deleteItem(a.id)
        assertTrue(store.search("Kyoto", "Japan").isEmpty())
        assertEquals(1, store.library().size)
    }
    @Test fun networkIsDisabledUntilExplicitlyEnabled() {
        val web = WebTools(base)
        val error = runCatching { web.search("Kyoto", false) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Enable Web allowed"))
    }
    @Test fun personalPackCollectsLocalItemsAndSurvivesReopen() {
        store.createPack("Kyoto weekend", "Train and food notes")
        val note = store.saveItem("Station", "Kyoto station lockers", "Kyoto weekend")
        assertEquals(1, store.packs().single().itemCount)
        assertEquals("Train and food notes", store.packs().single().description)
        store.close(); store = PersonalStore(context)
        assertEquals("Kyoto weekend", store.packs().single().name)
        assertEquals("Station", store.search("lockers", "Kyoto weekend").single().title)
        store.deleteItem(note.id)
        assertEquals(0, store.packs().single().itemCount)
    }
}
