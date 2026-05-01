package com.offgrid.shared.knowledge

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports a Knowledge Pack ZIP produced by `backend/factory/` into a local
 * directory layout the app can query:
 *
 *   <packsRoot>/<id>/manifest.json   (raw, copied from ZIP)
 *   <packsRoot>/<id>/sources.json    (raw, copied from ZIP)
 *   <packsRoot>/<id>/chunks.db       (SQLite + FTS5, built from chunks.jsonl)
 *   <packsRoot>/<id>/embeddings.f32  (raw, kept for future on-device retrieval)
 *   <packsRoot>/<id>/.imported       (sentinel: import succeeded)
 *
 * Idempotent: if `.imported` exists for a pack id, [importIfNeeded] is a no-op.
 *
 * Phase 2 keeps things simple: lexical BM25 only (FTS5). The embeddings.f32
 * file is preserved on disk so a Phase 3 hybrid retriever can plug in without
 * re-importing.
 */
class AndroidPackImporter(private val packsRoot: File) {

    /**
     * Imports the pack ZIP if no matching `<id>` directory has been imported
     * already. Returns the resulting [KnowledgePack] metadata, or null on a
     * malformed ZIP.
     */
    fun importIfNeeded(zipFile: File): KnowledgePack? {
        require(zipFile.exists()) { "pack zip not found: $zipFile" }
        // Peek manifest to learn the pack id without materializing everything.
        val previewManifest = peekManifest(zipFile) ?: run {
            Log.w(TAG, "import skipped: ${zipFile.name} has no manifest.json")
            return null
        }
        val packDir = File(packsRoot, previewManifest.id)
        if (File(packDir, IMPORTED_SENTINEL).exists()) {
            return previewManifest
        }
        return importFresh(zipFile, packDir)
    }

    /**
     * Reads an already-imported pack's manifest, returning null if the pack
     * dir has no manifest (or is mid-import).
     */
    fun readManifest(packDir: File): KnowledgePack? {
        val file = File(packDir, "manifest.json")
        if (!file.exists() || !File(packDir, IMPORTED_SENTINEL).exists()) return null
        return parseManifest(file.readText())
    }

    private fun importFresh(zipFile: File, packDir: File): KnowledgePack? {
        if (packDir.exists()) packDir.deleteRecursively()
        packDir.mkdirs()

        var manifestJson: String? = null
        var chunksFile: File? = null
        var sourcesJson: String? = null

        ZipInputStream(zipFile.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val safeName = sanitizeEntryName(entry.name) ?: continue
                val out = File(packDir, safeName)
                out.parentFile?.mkdirs()
                out.outputStream().use { it.write(zin.readBytes()) }
                when (safeName) {
                    "manifest.json" -> manifestJson = out.readText()
                    "chunks.jsonl" -> chunksFile = out
                    "sources.json" -> sourcesJson = out.readText()
                }
            }
        }

        val manifestText = manifestJson ?: run {
            Log.w(TAG, "import aborted: missing manifest.json in ${zipFile.name}")
            packDir.deleteRecursively()
            return null
        }
        val manifest = parseManifest(manifestText)
        val chunks = chunksFile ?: run {
            Log.w(TAG, "import aborted: missing chunks.jsonl in ${zipFile.name}")
            packDir.deleteRecursively()
            return null
        }
        val sources = sourcesJson ?: "[]"

        // Build the on-device search DB.
        val dbFile = File(packDir, "chunks.db")
        if (dbFile.exists()) dbFile.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.beginTransaction()
            createSchema(db)
            insertSources(db, sources)
            insertChunks(db, chunks)
            db.setTransactionSuccessful()
        } catch (t: Throwable) {
            Log.e(TAG, "import failed building DB for ${manifest.id}", t)
            db.endTransaction()
            db.close()
            packDir.deleteRecursively()
            return null
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }

        File(packDir, IMPORTED_SENTINEL).writeText(manifest.id)
        Log.i(
            TAG,
            "imported pack ${manifest.id}@${manifest.version} chunks=${manifest.numChunks} sources=${manifest.numSources}"
        )
        return manifest
    }

    private fun peekManifest(zipFile: File): KnowledgePack? {
        ZipInputStream(zipFile.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (!entry.isDirectory && entry.name == "manifest.json") {
                    return parseManifest(zin.readBytes().decodeToString())
                }
            }
        }
        return null
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE chunk_meta (
                rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                chunk_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                section_path TEXT NOT NULL,
                position INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sources (
                source_id TEXT PRIMARY KEY,
                url TEXT,
                title TEXT,
                license TEXT,
                fetched_at TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE VIRTUAL TABLE chunks USING fts5(text)")
    }

    private fun insertSources(db: SQLiteDatabase, sourcesJson: String) {
        val arr = JSONArray(sourcesJson)
        val stmt = db.compileStatement(
            "INSERT INTO sources(source_id, url, title, license, fetched_at) VALUES (?, ?, ?, ?, ?)"
        )
        try {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                stmt.bindString(1, o.getString("sourceId"))
                stmt.bindString(2, o.optString("url"))
                stmt.bindString(3, o.optString("title"))
                stmt.bindString(4, o.optString("license"))
                stmt.bindString(5, o.optString("fetchedAt"))
                stmt.executeInsert()
                stmt.clearBindings()
            }
        } finally {
            stmt.close()
        }
    }

    private fun insertChunks(db: SQLiteDatabase, jsonlFile: File) {
        val metaInsert = db.compileStatement(
            "INSERT INTO chunk_meta(chunk_id, source_id, section_path, position) VALUES (?, ?, ?, ?)"
        )
        val ftsInsert = db.compileStatement("INSERT INTO chunks(rowid, text) VALUES (?, ?)")
        try {
            jsonlFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val o = JSONObject(line)
                    val chunkId = o.getString("id")
                    val text = o.getString("text")
                    val sourceId = o.getString("sourceId")
                    val sectionPath = o.optString("sectionPath", "")
                    val position = o.optInt("position", 0)
                    metaInsert.bindString(1, chunkId)
                    metaInsert.bindString(2, sourceId)
                    metaInsert.bindString(3, sectionPath)
                    metaInsert.bindLong(4, position.toLong())
                    val rowid = metaInsert.executeInsert()
                    metaInsert.clearBindings()
                    ftsInsert.bindLong(1, rowid)
                    ftsInsert.bindString(2, text)
                    ftsInsert.executeInsert()
                    ftsInsert.clearBindings()
                }
            }
        } finally {
            metaInsert.close()
            ftsInsert.close()
        }
    }

    /**
     * Reject ZIP entries with absolute paths or `..` segments to prevent
     * zip-slip extraction outside the pack directory.
     */
    private fun sanitizeEntryName(name: String): String? {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/')) return null
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) return null
        // Only accept the small whitelisted set we actually need.
        val flat = parts.joinToString("/")
        return if (flat in ALLOWED_ENTRIES) flat else null
    }

    private fun parseManifest(text: String): KnowledgePack {
        val o = JSONObject(text)
        val tagsArr = o.optJSONArray("tags")
        val tags = if (tagsArr != null) {
            (0 until tagsArr.length()).map { i -> tagsArr.getString(i) }
        } else {
            emptyList()
        }
        return KnowledgePack(
            id = o.getString("id"),
            title = o.getString("title"),
            description = o.optString("description"),
            version = o.optString("version", "0"),
            generatedAt = o.optString("generatedAt", ""),
            numChunks = o.optInt("numChunks", 0),
            numSources = o.optInt("numSources", 0),
            sizeBytes = o.optLong("sizeBytes", 0L),
            sha256 = o.optString("sha256", ""),
            tags = tags,
            embedModel = o.optString("embedModel", ""),
            embedDim = o.optInt("embedDim", 0)
        )
    }

    companion object {
        private const val TAG = "PackImporter"
        const val IMPORTED_SENTINEL = ".imported"
        private val ALLOWED_ENTRIES = setOf(
            "manifest.json",
            "chunks.jsonl",
            "embeddings.f32",
            "sources.json",
            "README.md"
        )
    }
}
