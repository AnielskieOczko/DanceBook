package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Score merging: keyword results keep the order the existing searches give them, semantic
 * results come from a vector-only query on the knowledge index, and the two are merged by tier
 * rather than by comparing scores (a ts_rank and a cosine distance are not comparable).
 *
 *  1. BOTH: found by keyword and by meaning, in keyword order.
 *  2. KEYWORD: the rest of the keyword matches, in keyword order.
 *  3. SEMANTIC: up to [HybridSearchService.SEMANTIC_LIMIT] extra items, nearest first.
 *
 * Inside a tier, hits are ordered by their rank in their own list, notes before figures on a
 * tie, so notes and figures interleave. With no semantic hits this is exactly the keyword
 * results, which is what keeps today's behaviour.
 */
@Service
class HybridSearchServiceImpl(
    private val materialService: MaterialService,
    private val danceFigureService: DanceFigureService,
    private val appUserService: AppUserService,
    private val commentRepository: CommentRepository,
    private val knowledgeChunkRepository: KnowledgeChunkRepository,
    private val embeddingModel: EmbeddingModel,
    private val embeddingRateLimiter: EmbeddingQueryRateLimiter
) : HybridSearchService {

    companion object {
        private val log = LoggerFactory.getLogger(HybridSearchServiceImpl::class.java)
        /** Chunks to fetch for the semantic leg: several chunks can belong to one note. */
        private const val SEMANTIC_CANDIDATES = 30
    }

    private data class Ranked(val hit: SearchHit, val rank: Int)

    @Transactional(readOnly = true)
    override fun search(query: String?, danceTypeId: UUID?, limit: Int): List<SearchHit> {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return emptyList()

        val keywordNotes = materialService.searchNotes(q, null, danceTypeId, limit)
        val keywordFigures = danceFigureService.findAll(
            typeIds = danceTypeId?.let { listOf(it) }, nameSearch = q
        ).take(limit)

        val keywordIds = (keywordNotes.mapNotNull { it.id } + keywordFigures.mapNotNull { it.id }).toSet()
        val semantic = semanticHits(q, danceTypeId, keywordIds)

        val both = mutableListOf<Ranked>()
        val keywordOnly = mutableListOf<Ranked>()
        keywordNotes.forEachIndexed { i, n ->
            val m = if (n.id in semantic.ids) SearchMatch.BOTH else SearchMatch.KEYWORD
            (if (m == SearchMatch.BOTH) both else keywordOnly) += Ranked(noteHit(n, m), i)
        }
        keywordFigures.forEachIndexed { i, f ->
            val m = if (f.id in semantic.ids) SearchMatch.BOTH else SearchMatch.KEYWORD
            (if (m == SearchMatch.BOTH) both else keywordOnly) += Ranked(figureHit(f, m), i)
        }

        val order = compareBy<Ranked>({ it.rank }, { it.hit.type })
        return both.sortedWith(order).map { it.hit } +
            keywordOnly.sortedWith(order).map { it.hit } +
            semantic.extra
    }

    private class Semantic(val ids: Set<UUID>, val extra: List<SearchHit>)

    private fun semanticHits(query: String, danceTypeId: UUID?, keywordIds: Set<UUID>): Semantic {
        val none = Semantic(emptySet(), emptyList())
        return try {
            if (!embeddingRateLimiter.tryAcquire()) {
                log.debug("Embedding rate limit reached, searching by keyword only")
                return none
            }
            val embedding = embeddingModel.embed(query)
            val chunks = knowledgeChunkRepository.hybridSearch(
                query = null,
                queryEmbedding = embedding,
                sourceTypes = listOf(KnowledgeSourceType.NOTE, KnowledgeSourceType.NOTE_COMMENT, KnowledgeSourceType.FIGURE),
                currentUser = appUserService.getCurrentUserOrNull(),
                limit = SEMANTIC_CANDIDATES
            )

            // Ordered, de-duplicated candidates: (kind, id) nearest first.
            val candidates = LinkedHashSet<Pair<SearchHitType, UUID>>()
            for (r in chunks) {
                when (r.chunk.sourceType) {
                    KnowledgeSourceType.NOTE -> candidates += SearchHitType.NOTE to r.chunk.sourceId
                    KnowledgeSourceType.NOTE_COMMENT -> commentRepository.findById(r.chunk.sourceId).orElse(null)
                        ?.material?.id?.let { candidates += SearchHitType.NOTE to it }
                    KnowledgeSourceType.FIGURE -> candidates += SearchHitType.FIGURE to r.chunk.sourceId
                    else -> {}
                }
            }

            val ids = mutableSetOf<UUID>()
            val extra = mutableListOf<SearchHit>()
            for ((type, id) in candidates) {
                val inKeyword = id in keywordIds
                if (!inKeyword && extra.size >= HybridSearchService.SEMANTIC_LIMIT) continue
                // Opening the item re-applies the access rules, on top of the SQL filter.
                val hit = when (type) {
                    SearchHitType.NOTE -> openNote(id)?.takeIf { danceTypeId == null || it.danceType?.id == danceTypeId }
                        ?.let { noteHit(it, SearchMatch.SEMANTIC) }
                    SearchHitType.FIGURE -> openFigure(id)?.takeIf { danceTypeId == null || it.danceType?.id == danceTypeId }
                        ?.let { figureHit(it, SearchMatch.SEMANTIC) }
                } ?: continue
                if (inKeyword) ids += id else extra += hit
            }
            Semantic(ids, extra)
        } catch (e: Exception) {
            log.warn("Semantic search unavailable, falling back to keyword only: {}", e.message)
            none
        }
    }

    private fun openNote(id: UUID): Material? = try { materialService.findById(id) } catch (e: Exception) { null }
    private fun openFigure(id: UUID): DanceFigure? = try { danceFigureService.findById(id) } catch (e: Exception) { null }

    private fun noteHit(n: Material, m: SearchMatch) = SearchHit(SearchHitType.NOTE, n.id!!, m, note = n)
    private fun figureHit(f: DanceFigure, m: SearchMatch) = SearchHit(SearchHitType.FIGURE, f.id!!, m, figure = f)
}
