package com.offgrid.shared.knowledge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android implementation of [KnowledgePackStore].
 *
 * Storage layout, rooted at `getExternalFilesDir(null)/packs`:
 *
 *   packs/<id>.zip                 (sideloaded by `adb push`; auto-imported)
 *   packs/<id>/manifest.json       (extracted, immutable)
 *   packs/<id>/chunks.db           (built by AndroidPackImporter)
 *   packs/<id>/embeddings.f32      (kept; unused in Phase 2)
 *   packs/<id>/sources.json        (extracted, used by importer + UI)
 *   packs/<id>/.imported           (sentinel)
 *
 * Concurrency: refresh() and search() are guarded by a single mutex; pack DB
 * connections are kept open in [openDatabases] for the lifetime of the store.
 */
class AndroidKnowledgePackStore(
    context: Context
) : KnowledgePackStore {

    private val packsRoot: File = androidPacksRoot(context)
    private val importer = AndroidPackImporter(packsRoot)

    private val _installed = MutableStateFlow<List<KnowledgePack>>(emptyList())
    private val openDatabases = mutableMapOf<String, SQLiteDatabase>()
    private val sourceTitleCache = mutableMapOf<String, MutableMap<String, String>>()
    private val mutex = Mutex()

    override fun installed(): StateFlow<List<KnowledgePack>> = _installed.asStateFlow()

    override suspend fun refresh() = mutex.withLock {
        withContext(Dispatchers.IO) { refreshLocked() }
    }

    override suspend fun hasInstalledPack(packId: String): Boolean = mutex.withLock {
        _installed.value.any { it.id == packId }
    }

    override suspend fun search(query: String, topK: Int): List<RetrievedChunk> {
        val ftsQuery = toFtsQuery(query)
        if (ftsQuery.isEmpty()) return emptyList()
        return mutex.withLock {
            withContext(Dispatchers.IO) { searchLocked(ftsQuery, topK) }
        }
    }

    override suspend fun delete(packId: String): Boolean {
        return mutex.withLock {
            withContext(Dispatchers.IO) { deleteLocked(packId) }
        }
    }

    /**
     * Closes all open pack DBs. Call from the activity / process teardown if
     * you want clean shutdown; otherwise SQLite handles will be closed when
     * the process exits.
     */
    fun close() {
        synchronized(openDatabases) {
            for (db in openDatabases.values) {
                runCatching { db.close() }
            }
            openDatabases.clear()
            sourceTitleCache.clear()
        }
    }

    /** Synchronous re-scan, called under mutex on the IO dispatcher. */
    private fun refreshLocked() {
        // 1) Auto-import any sideloaded ZIPs.
        val zipFiles = packsRoot.listFiles { f -> f.isFile && f.name.endsWith(".zip") } ?: emptyArray()
        for (zip in zipFiles) {
            try {
                importer.importIfNeeded(zip)
            } catch (t: Throwable) {
                Log.w(TAG, "import failed for ${zip.name}: ${t.message}")
            }
        }

        // 2) Re-list imported packs from disk.
        val packDirs = packsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        val newPacks = mutableListOf<KnowledgePack>()
        val newDatabases = mutableMapOf<String, SQLiteDatabase>()

        for (dir in packDirs.sortedBy { it.name }) {
            val manifest = importer.readManifest(dir) ?: continue
            val dbFile = File(dir, "chunks.db")
            if (!dbFile.exists()) continue
            val db = openDatabases.remove(manifest.id) ?: try {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (t: Throwable) {
                Log.w(TAG, "could not open ${dbFile.name}: ${t.message}")
                continue
            }
            newDatabases[manifest.id] = db
            newPacks += manifest
        }

        // 3) Close any DBs whose pack directories disappeared.
        for ((_, db) in openDatabases) {
            runCatching { db.close() }
        }
        openDatabases.clear()
        openDatabases.putAll(newDatabases)
        sourceTitleCache.keys.retainAll(newDatabases.keys)

        _installed.value = newPacks.toList()
        Log.i(TAG, "refresh: ${newPacks.size} pack(s) installed")
    }

    /**
     * Synchronous delete, called under [mutex] on IO dispatcher.
     *
     * Removes:
     * - extracted dir packs/<id>/
     * - optional sideloaded zip packs/<id>.zip
     * - open SQLite handle + cached source-title map
     * Then refreshes the in-memory installed list.
     */
    private fun deleteLocked(packId: String): Boolean {
        var changed = false
        openDatabases.remove(packId)?.let { db ->
            runCatching { db.close() }
            changed = true
        }
        sourceTitleCache.remove(packId)

        val packDir = File(packsRoot, packId)
        if (packDir.exists()) {
            changed = packDir.deleteRecursively() || changed
        }
        val zipFile = File(packsRoot, "$packId.zip")
        if (zipFile.exists()) {
            changed = zipFile.delete() || changed
        }
        if (changed) {
            refreshLocked()
            Log.i(TAG, "deleted pack: $packId")
        } else {
            Log.i(TAG, "delete skipped (not found): $packId")
        }
        return changed
    }

    private fun searchLocked(ftsQuery: String, topK: Int): List<RetrievedChunk> {
        val packs = _installed.value
        if (packs.isEmpty()) return emptyList()

        val candidates = mutableListOf<Triple<Double, KnowledgePack, RowFromDb>>()
        for (pack in packs) {
            val db = openDatabases[pack.id] ?: continue
            try {
                queryPack(db, pack.id, ftsQuery, topK).forEach { row ->
                    candidates += Triple(row.score, pack, row)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "search failed in pack ${pack.id}: ${t.message}")
            }
        }
        // bm25() returns a NEGATIVE score where lower (more negative) = better.
        // Sort ascending by score, then take topK.
        return candidates
            .sortedBy { it.first }
            .take(topK)
            .map { (_, pack, row) ->
                val titles = sourceTitlesFor(pack.id)
                val sourceLabel = titles[row.sourceId] ?: pack.title
                RetrievedChunk(
                    text = row.text,
                    sourceLabel = sourceLabel,
                    sectionPath = row.sectionPath,
                    packId = pack.id
                )
            }
    }

    private fun queryPack(
        db: SQLiteDatabase,
        packId: String,
        ftsQuery: String,
        topK: Int
    ): List<RowFromDb> {
        val useFts5 = readFtsMode(db)
        if (!useFts5) {
            val cap = (topK * 25).coerceAtMost(500)
            val sql = """
                SELECT cm.source_id, cm.section_path, c.text
                FROM chunks c
                JOIN chunk_meta cm ON cm.rowid = c.rowid
                WHERE chunks MATCH ?
                LIMIT ?
            """.trimIndent()
            val cursor = db.rawQuery(sql, arrayOf(ftsQuery, cap.toString()))
            val rows = mutableListOf<RowFromDb>()
            cursor.use {
                while (it.moveToNext()) {
                    val text = it.getString(2) ?: ""
                    rows += RowFromDb(
                        sourceId = it.getString(0),
                        sectionPath = it.getString(1) ?: "",
                        text = text,
                        score = fts4TokenOverlapScore(text, ftsQuery)
                    )
                }
            }
            if (rows.isEmpty()) {
                Log.d(TAG, "no hits in $packId for query: $ftsQuery")
            }
            return rows.sortedBy { it.score }.take(topK)
        }

        val sql = """
            SELECT cm.source_id, cm.section_path, c.text, bm25(chunks) AS score
            FROM chunks c
            JOIN chunk_meta cm ON cm.rowid = c.rowid
            WHERE chunks MATCH ?
            ORDER BY score
            LIMIT ?
        """.trimIndent()
        val cursor = db.rawQuery(sql, arrayOf(ftsQuery, topK.toString()))
        val rows = mutableListOf<RowFromDb>()
        cursor.use {
            while (it.moveToNext()) {
                rows += RowFromDb(
                    sourceId = it.getString(0),
                    sectionPath = it.getString(1) ?: "",
                    text = it.getString(2),
                    score = it.getDouble(3)
                )
            }
        }
        if (rows.isEmpty()) {
            // Mention pack id only at debug volume; this is the common case.
            Log.d(TAG, "no hits in $packId for query: $ftsQuery")
        }
        return rows
    }

    private fun readFtsMode(db: SQLiteDatabase): Boolean {
        try {
            db.rawQuery("SELECT mode FROM _fts_cfg LIMIT 1", null).use { c ->
                if (c.moveToFirst()) {
                    return c.getString(0) == "5"
                }
            }
        } catch (_: Throwable) {
            // Older DBs: infer from sqlite_master.
        }
        return try {
            db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='chunks'",
                null
            ).use { c ->
                c.moveToFirst() && (c.getString(0)?.contains("fts5", ignoreCase = true) == true)
            }
        } catch (_: Throwable) {
            true
        }
    }

    /** Lower (more negative) = more token hits — aligns sort direction with bm25(). */
    private fun fts4TokenOverlapScore(text: String, ftsQuery: String): Double {
        val terms = ftsQuery.split(Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim().removeSurrounding("\"").lowercase() }
            .filter { it.length > 2 }
        if (terms.isEmpty()) return 0.0
        val hay = text.lowercase()
        var hits = 0
        for (t in terms) {
            var from = 0
            while (true) {
                val idx = hay.indexOf(t, from)
                if (idx < 0) break
                hits++
                from = idx + t.length
            }
        }
        return -hits.toDouble()
    }

    private fun sourceTitlesFor(packId: String): Map<String, String> {
        sourceTitleCache[packId]?.let { return it }
        val map = mutableMapOf<String, String>()
        val db = openDatabases[packId] ?: return map
        val cursor = db.rawQuery("SELECT source_id, title FROM sources", null)
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                val title = it.getString(1) ?: id
                if (id != null) map[id] = title
            }
        }
        sourceTitleCache[packId] = map
        return map
    }

    /**
     * Convert a free-text user query into an FTS5 MATCH expression.
     *
     * Strategy: lowercase, strip non-alphanumerics, drop very short words and a
     * small stopword list, OR-join with double quotes. Quotes prevent FTS5
     * from interpreting any reserved tokens.
     *
     * Returns "" when nothing useful remained — caller should treat that as
     * "no retrieval available, fall back to bare query".
     */
    private fun toFtsQuery(raw: String): String {
        val tokens = raw
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in STOPWORDS }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "\"$it\"" }
    }

    private data class RowFromDb(
        val sourceId: String,
        val sectionPath: String,
        val text: String,
        val score: Double
    )

    companion object {
        private const val TAG = "PackStore"
        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "are", "was", "but",
            "you", "your", "what", "which", "who", "how", "why", "when", "where",
            "from", "they", "them", "have", "has", "had", "into", "about"
        )
    }
}
