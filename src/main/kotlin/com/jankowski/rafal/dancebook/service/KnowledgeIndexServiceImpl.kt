package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.GoogleAiProperties
import com.jankowski.rafal.dancebook.model.KnowledgeChunk
import com.jankowski.rafal.dancebook.model.KnowledgeSourceType
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.repository.ChoreographyRepository
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.KnowledgeChunkRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@Service
class KnowledgeIndexServiceImpl(
    private val knowledgeChunkRepository: KnowledgeChunkRepository,
    private val materialRepository: MaterialRepository,
    private val commentRepository: CommentRepository,
    private val danceFigureRepository: DanceFigureRepository,
    private val choreographyRepository: ChoreographyRepository,
    private val richTextService: RichTextService,
    private val embeddingModel: EmbeddingModel,
    private val googleAiProperties: GoogleAiProperties,
    private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate
) : KnowledgeIndexService {

    companion object {
        private val log = LoggerFactory.getLogger(KnowledgeIndexServiceImpl::class.java)
        private const val MAX_CHUNK_CHARS = 1500
        private const val MAX_RETRIES = 5
    }

    data class QueueItem(
        val sourceType: KnowledgeSourceType,
        val sourceId: UUID,
        val attempt: Int = 1,
        val nextRetryTime: Long = System.currentTimeMillis()
    )

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private val inFlight = ConcurrentHashMap.newKeySet<Pair<KnowledgeSourceType, UUID>>()

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "knowledge-index-worker").apply { isDaemon = true }
    }

    @PreDestroy
    fun destroy() {
        executor.shutdown()
    }

    override fun queueIndex(sourceType: KnowledgeSourceType, sourceId: UUID) {
        val key = sourceType to sourceId
        if (inFlight.add(key)) {
            queue.add(QueueItem(sourceType, sourceId))
        }
        triggerProcessing()
    }

    override fun processQueueAsync() {
        triggerProcessing()
    }

    private fun triggerProcessing() {
        executor.execute {
            try {
                processQueue()
            } catch (t: Throwable) {
                log.error("Unexpected error in knowledge index worker", t)
            }
        }
    }

    override fun processQueue() {
        val now = System.currentTimeMillis()
        val retryLater = mutableListOf<QueueItem>()
        var earliestRetryDelayMs: Long? = null

        while (true) {
            val item = queue.poll() ?: break
            if (item.nextRetryTime > now) {
                retryLater.add(item)
                val delay = item.nextRetryTime - now
                earliestRetryDelayMs = minOf(earliestRetryDelayMs ?: Long.MAX_VALUE, delay)
                continue
            }

            val key = item.sourceType to item.sourceId
            try {
                transactionTemplate.execute {
                    when (item.sourceType) {
                        KnowledgeSourceType.NOTE -> indexMaterial(item.sourceId)
                        KnowledgeSourceType.NOTE_COMMENT -> indexComment(item.sourceId)
                        KnowledgeSourceType.FIGURE -> indexFigure(item.sourceId)
                        KnowledgeSourceType.CHOREOGRAPHY -> indexChoreography(item.sourceId)
                    }
                }
                inFlight.remove(key)
            } catch (e: Exception) {
                log.error("Failed to index {} {} (attempt {}): {}", item.sourceType, item.sourceId, item.attempt, e.message, e)
                if (item.attempt < MAX_RETRIES) {
                    val backoffMs = (1L shl item.attempt) * 1000L
                    val retryItem = item.copy(attempt = item.attempt + 1, nextRetryTime = now + backoffMs)
                    retryLater.add(retryItem)
                    earliestRetryDelayMs = minOf(earliestRetryDelayMs ?: Long.MAX_VALUE, backoffMs)
                } else {
                    inFlight.remove(key)
                    log.error("Giving up on indexing {} {} after {} attempts", item.sourceType, item.sourceId, item.attempt, e)
                }
            }
        }

        queue.addAll(retryLater)

        earliestRetryDelayMs?.let { delay ->
            val safeDelay = maxOf(50L, delay)
            executor.schedule({
                try {
                    processQueue()
                } catch (t: Throwable) {
                    log.error("Unexpected error in scheduled retry", t)
                }
            }, safeDelay, TimeUnit.MILLISECONDS)
        }
    }

    @Transactional
    override fun indexMaterial(materialId: UUID) {
        val material = materialRepository.findById(materialId).orElse(null)
        if (material == null) {
            deleteChunks(KnowledgeSourceType.NOTE, materialId)
            return
        }

        val title = material.name
        val dance = material.danceType?.name.orEmpty()
        val figures = material.figures.mapNotNull { pin ->
            pin.danceFigure?.let { df ->
                listOfNotNull(
                    df.name,
                    df.alternativeTiming?.takeIf { it.isNotBlank() },
                    if (pin.startTime > 0 || pin.endTime > 0) "${pin.startTime}s-${pin.endTime}s" else null
                ).joinToString(" · ")
            }
        }.joinToString(", ")

        val plainText = richTextService.toPlainText(material.description).orEmpty()
        val paragraphs = splitParagraphs(plainText, MAX_CHUNK_CHARS)

        val headerLines = mutableListOf("Note: $title")
        if (dance.isNotBlank()) headerLines.add("Dance: $dance")
        if (figures.isNotBlank()) headerLines.add("Figures: $figures")
        val header = headerLines.joinToString("\n")

        val rawChunks = if (paragraphs.isEmpty()) {
            listOf(header)
        } else {
            paragraphs.map { p -> "$header\n\n$p" }
        }

        val chunks = rawChunks.mapIndexed { idx, content ->
            KnowledgeChunk(
                sourceType = KnowledgeSourceType.NOTE,
                sourceId = material.id!!,
                chunkIndex = idx,
                content = content,
                embeddingModel = googleAiProperties.embeddingModel,
                ownerId = material.owner?.id,
                visibility = material.visibility
            )
        }

        saveChunksWithEmbeddings(KnowledgeSourceType.NOTE, material.id!!, chunks)
    }

    @Transactional
    override fun indexComment(commentId: UUID) {
        val comment = commentRepository.findById(commentId).orElse(null)
        if (comment == null) {
            deleteChunks(KnowledgeSourceType.NOTE_COMMENT, commentId)
            return
        }

        val noteTitle = comment.material?.name.orEmpty()
        val plain = richTextService.toPlainText(comment.content).orEmpty()
        val content = "Note: $noteTitle\nComment: $plain"

        val chunk = KnowledgeChunk(
            sourceType = KnowledgeSourceType.NOTE_COMMENT,
            sourceId = comment.id!!,
            chunkIndex = 0,
            content = content,
            embeddingModel = googleAiProperties.embeddingModel,
            ownerId = comment.material?.owner?.id,
            visibility = comment.material?.visibility ?: Visibility.PRIVATE
        )

        saveChunksWithEmbeddings(KnowledgeSourceType.NOTE_COMMENT, comment.id!!, listOf(chunk))
    }

    @Transactional
    override fun indexFigure(danceFigureId: UUID) {
        val figure = danceFigureRepository.findById(danceFigureId).orElse(null)
        if (figure == null) {
            deleteChunks(KnowledgeSourceType.FIGURE, danceFigureId)
            return
        }

        val steps = figure.steps
        val stepSummary = if (steps.isNotEmpty()) {
            steps.joinToString("; ") { "${it.role} #${it.stepNumber}: ${it.action} ${it.foot} ${it.timing}".trim() }
        } else ""

        val pos = listOfNotNull(
            figure.startingPosition?.takeIf { it.isNotBlank() }?.let { "Starts: $it" },
            figure.endingPosition?.takeIf { it.isNotBlank() }?.let { "Ends: $it" }
        ).joinToString(", ")

        val notes = richTextService.toPlainText(figure.notes).orEmpty()

        val content = listOfNotNull(
            "Figure: ${figure.name}",
            figure.danceType?.name?.let { "Dance: $it" },
            figure.danceClass?.displayName?.let { "Class: $it" },
            figure.alternativeTiming?.takeIf { it.isNotBlank() }?.let { "Timing: $it" },
            pos.takeIf { it.isNotBlank() }?.let { "Positions: $it" },
            notes.takeIf { it.isNotBlank() }?.let { "Notes: $it" },
            stepSummary.takeIf { it.isNotBlank() }?.let { "Steps: $it" }
        ).joinToString("\n")

        val chunk = KnowledgeChunk(
            sourceType = KnowledgeSourceType.FIGURE,
            sourceId = figure.id!!,
            chunkIndex = 0,
            content = content,
            embeddingModel = googleAiProperties.embeddingModel,
            ownerId = figure.createdBy?.id,
            visibility = Visibility.PUBLIC
        )

        saveChunksWithEmbeddings(KnowledgeSourceType.FIGURE, figure.id!!, listOf(chunk))
    }

    @Transactional
    override fun indexChoreography(choreographyId: UUID) {
        val choreography = choreographyRepository.findById(choreographyId).orElse(null)
        if (choreography == null) {
            deleteChunks(KnowledgeSourceType.CHOREOGRAPHY, choreographyId)
            return
        }

        val dance = choreography.danceType?.name.orEmpty()
        val desc = richTextService.toPlainText(choreography.description).orEmpty()
        val sequence = choreography.entries.sortedBy { it.sortOrder }.mapNotNull {
            it.danceFigure?.name ?: it.notes ?: it.sectionLabel
        }.joinToString(" -> ")

        val content = listOfNotNull(
            "Choreography: ${choreography.name}",
            dance.takeIf { it.isNotBlank() }?.let { "Dance: $it" },
            desc.takeIf { it.isNotBlank() }?.let { "Description: $it" },
            sequence.takeIf { it.isNotBlank() }?.let { "Figure Sequence: $it" }
        ).joinToString("\n")

        val chunk = KnowledgeChunk(
            sourceType = KnowledgeSourceType.CHOREOGRAPHY,
            sourceId = choreography.id!!,
            chunkIndex = 0,
            content = content,
            embeddingModel = googleAiProperties.embeddingModel,
            ownerId = choreography.owner?.id,
            visibility = choreography.visibility
        )

        saveChunksWithEmbeddings(KnowledgeSourceType.CHOREOGRAPHY, choreography.id!!, listOf(chunk))
    }

    private fun saveChunksWithEmbeddings(
        sourceType: KnowledgeSourceType,
        sourceId: UUID,
        chunks: List<KnowledgeChunk>
    ) {
        knowledgeChunkRepository.deleteBySource(sourceType, sourceId)

        for (chunk in chunks) {
            var embedding: FloatArray? = null
            try {
                embedding = embeddingModel.embed(chunk.content)
            } catch (e: Exception) {
                log.warn("Embedding API call failed for {} {}: {}. Full-text index active.", sourceType, sourceId, e.message)
            }
            knowledgeChunkRepository.save(chunk.copy(embedding = embedding))
        }
    }

    override fun deleteChunks(sourceType: KnowledgeSourceType, sourceId: UUID) {
        knowledgeChunkRepository.deleteBySource(sourceType, sourceId)
    }

    override fun deleteComments(commentIds: Collection<UUID>) {
        knowledgeChunkRepository.deleteComments(commentIds)
    }

    override fun deleteCommentsForMaterial(materialId: UUID) {
        knowledgeChunkRepository.deleteCommentsForMaterial(materialId)
    }

    override fun updateVisibility(sourceType: KnowledgeSourceType, sourceId: UUID, visibility: Visibility) {
        knowledgeChunkRepository.updateVisibility(sourceType, sourceId, visibility)
    }

    override fun updateCommentsVisibilityForMaterial(materialId: UUID, visibility: Visibility) {
        knowledgeChunkRepository.updateCommentsVisibilityForMaterial(materialId, visibility)
    }

    override fun isStale(): Boolean {
        return knowledgeChunkRepository.countStale(googleAiProperties.embeddingModel) > 0
    }

    @Transactional
    override fun rebuildAll(): RebuildReport {
        val start = System.currentTimeMillis()
        log.info("Starting knowledge index rebuild with model {}", googleAiProperties.embeddingModel)

        knowledgeChunkRepository.deleteAll()

        var noteCount = 0
        materialRepository.findAll().forEach {
            indexMaterial(it.id!!)
            noteCount++
        }

        var commentCount = 0
        commentRepository.findAll().forEach {
            indexComment(it.id!!)
            commentCount++
        }

        var figureCount = 0
        danceFigureRepository.findAll().forEach {
            indexFigure(it.id!!)
            figureCount++
        }

        var choreographyCount = 0
        choreographyRepository.findAll().forEach {
            indexChoreography(it.id!!)
            choreographyCount++
        }

        val totalChunks = knowledgeChunkRepository.countAll().toInt()
        val duration = System.currentTimeMillis() - start
        log.info("Rebuild completed in {}ms: {} notes, {} comments, {} figures, {} choreographies, {} chunks",
            duration, noteCount, commentCount, figureCount, choreographyCount, totalChunks)

        return RebuildReport(noteCount, commentCount, figureCount, choreographyCount, totalChunks, duration)
    }

    private fun splitParagraphs(text: String, maxChars: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val paragraphs = text.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()

        for (p in paragraphs) {
            if (current.isNotEmpty() && current.length + p.length + 2 > maxChars) {
                chunks.add(current.toString())
                current.clear()
            }
            if (p.length > maxChars) {
                // Large paragraph split by sentence or newline
                val sentences = p.split(Regex("(?<=[.!?])\\s+"))
                for (s in sentences) {
                    if (current.isNotEmpty() && current.length + s.length + 1 > maxChars) {
                        chunks.add(current.toString())
                        current.clear()
                    }
                    if (current.isNotEmpty()) current.append(" ")
                    current.append(s)
                }
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(p)
            }
        }
        if (current.isNotEmpty()) {
            chunks.add(current.toString())
        }
        return chunks
    }
}
