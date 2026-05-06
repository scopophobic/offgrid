package com.offgrid.shared.knowledge

import kotlinx.coroutines.flow.StateFlow

/**
 * Local store of installed Knowledge Packs and the retrieval surface over them.
 *
 * The store owns:
 *   - the list of currently-installed packs (reactive via [installed])
 *   - lookup of relevant chunks for a query (BM25 over each pack's FTS index)
 *
 * This sits behind [HybridRetriever] which formats the prompt; callers do not
 * inject context themselves.
 */
interface KnowledgePackStore {

    /**
     * Currently-installed packs. Updates after [refresh] discovers/imports new
     * pack files or after a pack is deleted.
     */
    fun installed(): StateFlow<List<KnowledgePack>>

    /**
     * Re-scan local storage for new pack ZIPs and import any that aren't
     * already imported. Cheap to call repeatedly: imported packs are skipped.
     */
    suspend fun refresh()

    /** True if [packId] is in the last refreshed installed list (under store lock). */
    suspend fun hasInstalledPack(packId: String): Boolean

    /**
     * Top-K chunks across all installed packs. Implementations should rank by
     * BM25 (or equivalent) and return at most [topK] results. Returns an empty
     * list when no packs are installed or no chunk matches the query.
     */
    suspend fun search(query: String, topK: Int = 4): List<RetrievedChunk>

    /**
     * Delete an installed pack and all local artifacts derived from it (DB,
     * manifest, embeddings, extracted files). Returns true when something was
     * deleted, false when the pack id was not present.
     */
    suspend fun delete(packId: String): Boolean
}
