package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.KnowledgeChunk
import com.jankowski.rafal.dancebook.model.KnowledgeSearchResult
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Visibility
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class KnowledgeChunkRepository(
    private val jdbcTemplate: JdbcTemplate
) {

    private val rowMapper = RowMapper<KnowledgeChunk> { rs, _ ->
        val embStr = rs.getString("embedding")
        val embedding = embStr?.removeSurrounding("[", "]")
            ?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim().toFloat() }
            ?.toFloatArray()

        KnowledgeChunk(
            id = UUID.fromString(rs.getString("id")),
            sourceType = KnowledgeSourceType.valueOf(rs.getString("source_type")),
            sourceId = UUID.fromString(rs.getString("source_id")),
            chunkIndex = rs.getInt("chunk_index"),
            content = rs.getString("content"),
            embedding = embedding,
            embeddingModel = rs.getString("embedding_model"),
            ownerId = rs.getString("owner_id")?.let { UUID.fromString(it) },
            visibility = Visibility.valueOf(rs.getString("visibility")),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    fun save(chunk: KnowledgeChunk) {
        val embStr = chunk.embedding?.let { "[${it.joinToString(",")}]" }
        val sql = """
            INSERT INTO knowledge_chunk (id, source_type, source_id, chunk_index, content, embedding, embedding_model, owner_id, visibility, updated_at)
            VALUES (?, ?, ?, ?, ?, cast(? as vector), ?, ?, ?, ?)
            ON CONFLICT (source_type, source_id, chunk_index) DO UPDATE
            SET content = EXCLUDED.content,
                embedding = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model,
                owner_id = EXCLUDED.owner_id,
                visibility = EXCLUDED.visibility,
                updated_at = EXCLUDED.updated_at
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            chunk.id,
            chunk.sourceType.name,
            chunk.sourceId,
            chunk.chunkIndex,
            chunk.content,
            embStr,
            chunk.embeddingModel,
            chunk.ownerId,
            chunk.visibility.name,
            Timestamp.valueOf(chunk.updatedAt)
        )
    }

    fun saveAll(chunks: List<KnowledgeChunk>) {
        chunks.forEach { save(it) }
    }

    fun deleteBySource(sourceType: KnowledgeSourceType, sourceId: UUID) {
        jdbcTemplate.update(
            "DELETE FROM knowledge_chunk WHERE source_type = ? AND source_id = ?",
            sourceType.name, sourceId
        )
    }

    fun deleteCommentsForMaterial(materialId: UUID) {
        jdbcTemplate.update(
            "DELETE FROM knowledge_chunk WHERE source_type = 'NOTE_COMMENT' AND source_id IN (SELECT id FROM comment WHERE material_id = ?)",
            materialId
        )
    }

    fun deleteComments(commentIds: Collection<UUID>) {
        if (commentIds.isEmpty()) return
        val placeholders = commentIds.joinToString(",") { "?" }
        val params = mutableListOf<Any>(KnowledgeSourceType.NOTE_COMMENT.name)
        params.addAll(commentIds)
        jdbcTemplate.update(
            "DELETE FROM knowledge_chunk WHERE source_type = ? AND source_id IN ($placeholders)",
            *params.toTypedArray()
        )
    }

    fun updateVisibility(sourceType: KnowledgeSourceType, sourceId: UUID, visibility: Visibility) {
        jdbcTemplate.update(
            "UPDATE knowledge_chunk SET visibility = ?, updated_at = NOW() WHERE source_type = ? AND source_id = ?",
            visibility.name, sourceType.name, sourceId
        )
    }

    fun updateCommentsVisibilityForMaterial(materialId: UUID, visibility: Visibility) {
        jdbcTemplate.update(
            "UPDATE knowledge_chunk SET visibility = ?, updated_at = NOW() WHERE source_type = 'NOTE_COMMENT' AND source_id IN (SELECT id FROM comment WHERE material_id = ?)",
            visibility.name, materialId
        )
    }

    fun updateEmbedding(id: UUID, embedding: FloatArray, modelName: String) {
        val embStr = "[${embedding.joinToString(",")}]"
        jdbcTemplate.update(
            "UPDATE knowledge_chunk SET embedding = cast(? as vector), embedding_model = ?, updated_at = NOW() WHERE id = ?",
            embStr, modelName, id
        )
    }

    fun findFirstChunkBySource(sourceType: KnowledgeSourceType, sourceId: UUID): KnowledgeChunk? {
        val list = jdbcTemplate.query(
            "SELECT * FROM knowledge_chunk WHERE source_type = ? AND source_id = ? ORDER BY chunk_index ASC LIMIT 1",
            rowMapper,
            sourceType.name, sourceId
        )
        return list.firstOrNull()
    }

    fun findBySource(sourceType: KnowledgeSourceType, sourceId: UUID): List<KnowledgeChunk> {
        return jdbcTemplate.query(
            "SELECT * FROM knowledge_chunk WHERE source_type = ? AND source_id = ? ORDER BY chunk_index ASC",
            rowMapper,
            sourceType.name, sourceId
        )
    }

    fun findChunksAwaitingEmbedding(limit: Int = 100): List<KnowledgeChunk> {
        return jdbcTemplate.query(
            "SELECT * FROM knowledge_chunk WHERE embedding IS NULL ORDER BY updated_at ASC LIMIT ?",
            rowMapper,
            limit
        )
    }

    fun countStale(modelName: String): Long {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM knowledge_chunk WHERE embedding_model != ? OR embedding IS NULL",
            Long::class.java,
            modelName
        ) ?: 0L
    }

    fun countAll(): Long {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM knowledge_chunk", Long::class.java) ?: 0L
    }

    fun deleteAll() {
        jdbcTemplate.update("DELETE FROM knowledge_chunk")
    }

    /**
     * Hybrid search using Reciprocal Rank Fusion (RRF) between vector similarity and full-text search.
     * Filtered by access rules: User B never sees User A's private notes or comments.
     */
    fun hybridSearch(
        query: String?,
        queryEmbedding: FloatArray?,
        sourceTypes: List<KnowledgeSourceType>? = null,
        currentUser: AppUser?,
        limit: Int = 10,
        topK: Int = 20,
        k: Int = 60
    ): List<KnowledgeSearchResult> {
        val hasVector = queryEmbedding != null
        val hasText = !query.isNullOrBlank()

        if (!hasVector && !hasText) {
            return emptyList()
        }

        val embStr = queryEmbedding?.let { "[${it.joinToString(",")}]" }
        val cleanQuery = query?.trim().orEmpty()

        val params = mutableListOf<Any?>()

        // 1. Access predicate
        val accessCondition = when {
            currentUser?.role == Role.ADMIN -> "TRUE"
            currentUser != null -> {
                params.add(currentUser.id)
                params.add(currentUser.id)
                "(visibility = 'PUBLIC' OR owner_id = ? OR (source_type = 'NOTE' AND EXISTS (SELECT 1 FROM share s WHERE s.item_type = 'MATERIAL' AND s.item_id = source_id AND s.grantee_user_id = ?)))"
            }
            else -> "(visibility = 'PUBLIC')"
        }

        // 2. Source type predicate
        val sourceCondition = if (!sourceTypes.isNullOrEmpty()) {
            val placeholders = sourceTypes.joinToString(",") { "?" }
            "source_type IN ($placeholders)"
        } else "TRUE"

        val vectorCteParams = mutableListOf<Any?>()
        val vectorCte = if (hasVector) {
            vectorCteParams.add(embStr)
            vectorCteParams.add(embStr)
            if (currentUser != null && currentUser.role != Role.ADMIN) {
                vectorCteParams.add(currentUser.id)
                vectorCteParams.add(currentUser.id)
            }
            if (!sourceTypes.isNullOrEmpty()) {
                sourceTypes.forEach { vectorCteParams.add(it.name) }
            }
            vectorCteParams.add(embStr)
            vectorCteParams.add(topK)
            """
            vector_matches AS (
                SELECT id, ROW_NUMBER() OVER (ORDER BY embedding <=> cast(? as vector)) AS rank
                FROM knowledge_chunk
                WHERE embedding IS NOT NULL
                  AND (embedding <=> cast(? as vector)) < 0.65
                  AND $accessCondition
                  AND $sourceCondition
                ORDER BY embedding <=> cast(? as vector)
                LIMIT ?
            )
            """.trimIndent()
        } else {
            """
            vector_matches AS (
                SELECT NULL::uuid AS id, NULL::bigint AS rank WHERE false
            )
            """.trimIndent()
        }

        val textCteParams = mutableListOf<Any?>()
        val textCte = if (hasText) {
            textCteParams.add(cleanQuery)
            textCteParams.add(cleanQuery)
            if (currentUser != null && currentUser.role != Role.ADMIN) {
                textCteParams.add(currentUser.id)
                textCteParams.add(currentUser.id)
            }
            if (!sourceTypes.isNullOrEmpty()) {
                sourceTypes.forEach { textCteParams.add(it.name) }
            }
            textCteParams.add(cleanQuery)
            textCteParams.add(topK)
            """
            text_matches AS (
                SELECT id, ROW_NUMBER() OVER (ORDER BY ts_rank(content_tsv, plainto_tsquery('simple', ?)) DESC) AS rank
                FROM knowledge_chunk
                WHERE content_tsv @@ plainto_tsquery('simple', ?)
                  AND $accessCondition
                  AND $sourceCondition
                ORDER BY ts_rank(content_tsv, plainto_tsquery('simple', ?)) DESC
                LIMIT ?
            )
            """.trimIndent()
        } else {
            """
            text_matches AS (
                SELECT NULL::uuid AS id, NULL::bigint AS rank WHERE false
            )
            """.trimIndent()
        }

        val allParams = mutableListOf<Any?>()
        allParams.addAll(vectorCteParams)
        allParams.addAll(textCteParams)
        allParams.add(k)
        allParams.add(k)
        allParams.add(limit)

        val sql = """
            WITH $vectorCte,
                 $textCte
            SELECT kc.*,
                   (COALESCE(1.0 / (? + vm.rank), 0.0) + COALESCE(1.0 / (? + tm.rank), 0.0)) AS rrf_score
            FROM knowledge_chunk kc
            LEFT JOIN vector_matches vm ON kc.id = vm.id
            LEFT JOIN text_matches tm ON kc.id = tm.id
            WHERE vm.id IS NOT NULL OR tm.id IS NOT NULL
            ORDER BY rrf_score DESC
            LIMIT ?
        """.trimIndent()

        return jdbcTemplate.query(sql, { rs, rowNum ->
            val chunk = rowMapper.mapRow(rs, rowNum)!!
            val rrf = rs.getDouble("rrf_score")
            KnowledgeSearchResult(chunk, rrf)
        }, *allParams.toTypedArray())
    }

    /**
     * Find top N related notes by vector similarity to a query embedding, excluding [excludeSourceId].
     */
    fun findRelatedNotes(
        queryEmbedding: FloatArray,
        excludeSourceId: UUID?,
        currentUser: AppUser?,
        limit: Int = 3
    ): List<KnowledgeChunk> {
        val embStr = "[${queryEmbedding.joinToString(",")}]"
        val params = mutableListOf<Any?>()
        params.add(embStr)

        val excludeCondition = if (excludeSourceId != null) {
            params.add(excludeSourceId)
            "source_id != ?"
        } else "TRUE"

        val accessCondition = when {
            currentUser?.role == Role.ADMIN -> "TRUE"
            currentUser != null -> {
                params.add(currentUser.id)
                params.add(currentUser.id)
                "(visibility = 'PUBLIC' OR owner_id = ? OR (source_type = 'NOTE' AND EXISTS (SELECT 1 FROM share s WHERE s.item_type = 'MATERIAL' AND s.item_id = source_id AND s.grantee_user_id = ?)))"
            }
            else -> "(visibility = 'PUBLIC')"
        }

        params.add(limit)

        val sql = """
            WITH ranked_notes AS (
                SELECT *,
                       ROW_NUMBER() OVER (PARTITION BY source_id ORDER BY chunk_index ASC) AS rn,
                       (embedding <=> cast(? as vector)) AS dist
                FROM knowledge_chunk
                WHERE source_type = 'NOTE'
                  AND $excludeCondition
                  AND embedding IS NOT NULL
                  AND $accessCondition
            )
            SELECT id, source_type, source_id, chunk_index, content, embedding, embedding_model, owner_id, visibility, updated_at
            FROM ranked_notes
            WHERE rn = 1
            ORDER BY dist ASC
            LIMIT ?
        """.trimIndent()

        return jdbcTemplate.query(sql, rowMapper, *params.toTypedArray())
    }
}
