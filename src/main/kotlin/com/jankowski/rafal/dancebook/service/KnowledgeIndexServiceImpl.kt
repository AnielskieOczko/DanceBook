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
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val transactionTemplate: TransactionTemplate,
    private val embeddingBudget: EmbeddingBudget
) : KnowledgeIndexService {

    companion object {
        private val log = LoggerFactory.getLogger(KnowledgeIndexServiceImpl::class.java)
        private const val MAX_CHUNK_CHARS = 1500
    }

    private val isWorkerRunning = AtomicBoolean(false)
    private val lastRequestTimestamp = AtomicLong(0L)
    private val failureCounts = ConcurrentHashMap<UUID, Int>()
    private val failureBackoffs = ConcurrentHashMap<UUID, Long>()
    private val consecutive429Count = AtomicInteger(0)
    private val globalBackoffUntil = AtomicLong(0L)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "knowledge-index-worker").apply { isDaemon = true }
    }

    @PreDestroy
    fun destroy() {
        executor.shutdown()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        log.info("Checking for knowledge chunks awaiting embedding on application ready...")
        triggerProcessing()
    }

    override fun queueIndex(sourceType: KnowledgeSourceType, sourceId: UUID) {
        try {
            transactionTemplate.execute {
                when (sourceType) {
                    KnowledgeSourceType.NOTE -> indexMaterial(sourceId)
                    KnowledgeSourceType.NOTE_COMMENT -> indexComment(sourceId)
                    KnowledgeSourceType.FIGURE -> indexFigure(sourceId)
                    KnowledgeSourceType.CHOREOGRAPHY -> indexChoreography(sourceId)
                }
            }
        } catch (e: Exception) {
            log.error("Failed to extract knowledge chunks for {} {}: {}", sourceType, sourceId, e.message, e)
        }
        triggerProcessingAfterCommit()
    }

    override fun processQueueAsync() {
        triggerProcessing()
    }

    override fun processQueue() {
        runEmbeddingLoop()
    }

    private fun triggerProcessingAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        triggerProcessing()
                    }
                }
            )
        } else {
            triggerProcessing()
        }
    }

    private fun triggerProcessing() {
        if (!isWorkerRunning.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            try {
                runEmbeddingLoop()
            } catch (t: Throwable) {
                log.error("Unexpected error in knowledge index worker", t)
            } finally {
                isWorkerRunning.set(false)
                checkAndScheduleNextRun()
            }
        }
    }

    private fun checkAndScheduleNextRun() {
        val pendingCount = knowledgeChunkRepository.countAwaitingEmbedding()
        if (pendingCount > 0) {
            val now = System.currentTimeMillis()
            val globalBackoff = globalBackoffUntil.get()
            failureBackoffs.entries.removeIf { it.value <= now }
            val activeBackoffs = failureBackoffs.values.filter { it > now }
            val nextBackoff = if (activeBackoffs.size >= pendingCount) {
                val earliestChunkBackoff = activeBackoffs.minOrNull() ?: now
                maxOf(globalBackoff, earliestChunkBackoff)
            } else {
                globalBackoff
            }

            if (nextBackoff > now) {
                val delay = nextBackoff - now
                executor.schedule({ triggerProcessing() }, maxOf(50L, delay), TimeUnit.MILLISECONDS)
            } else {
                executor.execute { triggerProcessing() }
            }
        }
    }

    private fun runEmbeddingLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val now = System.currentTimeMillis()
            val globalBackoff = globalBackoffUntil.get()
            if (globalBackoff > now) {
                val waitMs = globalBackoff - now
                try {
                    Thread.sleep(minOf(waitMs, 5000L))
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }

            val currentTime = System.currentTimeMillis()
            val backedOffIds = failureBackoffs.filterValues { it > currentTime }.keys
            val pendingChunks = knowledgeChunkRepository.findChunksAwaitingEmbedding(
                limit = 20,
                excludeIds = backedOffIds
            )
            if (pendingChunks.isEmpty()) {
                val earliest = failureBackoffs.values.filter { it > currentTime }.minOrNull()
                if (earliest != null && earliest > currentTime) {
                    val delay = earliest - currentTime
                    executor.schedule({ triggerProcessing() }, maxOf(50L, delay), TimeUnit.MILLISECONDS)
                }
                break
            }

            val eligibleChunks = pendingChunks.filter { chunk ->
                val retryAfter = failureBackoffs[chunk.id] ?: 0L
                retryAfter <= currentTime
            }

            if (eligibleChunks.isEmpty()) {
                val earliest = failureBackoffs.values.filter { it > currentTime }.minOrNull()
                if (earliest != null && earliest > currentTime) {
                    val delay = earliest - currentTime
                    executor.schedule({ triggerProcessing() }, maxOf(50L, delay), TimeUnit.MILLISECONDS)
                }
                break
            }

            for (chunk in eligibleChunks) {
                if (Thread.currentThread().isInterrupted) break

                val gb = globalBackoffUntil.get()
                if (gb > System.currentTimeMillis()) {
                    break
                }

                paceRateLimit()

                try {
                    val embedding = embeddingModel.embed(chunk.content)
                    knowledgeChunkRepository.updateEmbedding(chunk.id, embedding, googleAiProperties.embeddingModel)
                    failureCounts.remove(chunk.id)
                    failureBackoffs.remove(chunk.id)
                    consecutive429Count.set(0)
                } catch (e: Exception) {
                    if (isRateLimitException(e)) {
                        val delayMs = extractRetryDelayMs(e) ?: run {
                            val count = consecutive429Count.incrementAndGet()
                            minOf(60_000L, 2000L * (1L shl minOf(count - 1, 5)))
                        }
                        globalBackoffUntil.set(System.currentTimeMillis() + delayMs)
                        log.warn("Rate limit (429) on chunk {}. Backing off provider for {}ms: {}", chunk.id, delayMs, e.message)
                        break
                    } else {
                        val attempts = failureCounts.compute(chunk.id) { _, count -> (count ?: 0) + 1 }!!
                        val backoff = minOf(60_000L, 1000L * (1L shl minOf(attempts - 1, 6)))
                        failureBackoffs[chunk.id] = System.currentTimeMillis() + backoff
                        log.warn("Failed embedding chunk {} (attempt {}): {}. Retrying in {}ms", chunk.id, attempts, e.message, backoff)
                    }
                }
            }
        }
    }

    private fun paceRateLimit() {
        val rpm = googleAiProperties.embeddingRequestsPerMinute
        if (rpm > 0) {
            val minIntervalMs = 60_000L / rpm
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTimestamp.get()
            if (elapsed < minIntervalMs) {
                val sleepMs = minIntervalMs - elapsed
                Thread.sleep(sleepMs)
            }
        }
        lastRequestTimestamp.set(System.currentTimeMillis())
        // Searches draw on the same per-minute budget; wait here if they have used it up.
        embeddingBudget.acquireForWorker()
    }

    internal fun isRateLimitException(e: Throwable): Boolean {
        var curr: Throwable? = e
        while (curr != null) {
            val msg = curr.message.orEmpty()
            val className = curr.javaClass.name
            if (msg.contains("429") ||
                msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                msg.contains("Too Many Requests", ignoreCase = true) ||
                msg.contains("quota", ignoreCase = true) ||
                msg.contains("rate limit", ignoreCase = true) ||
                msg.contains("retry in", ignoreCase = true) ||
                msg.contains("retry after", ignoreCase = true) ||
                className.contains("TooManyRequests", ignoreCase = true)
            ) {
                return true
            }
            if (curr is org.springframework.web.client.HttpStatusCodeException && curr.statusCode.value() == 429) {
                return true
            }
            curr = curr.cause
        }
        return false
    }

    internal fun extractRetryDelayMs(e: Throwable): Long? {
        val patterns = listOf(
            Regex("""(?i)retry\s+(?:after|in)\s+(\d+(?:\.\d+)?)\s*s?"""),
            Regex("""(?i)retry[_\s]delay[:\s]+(\d+(?:\.\d+)?)\s*s?""")
        )

        var curr: Throwable? = e
        while (curr != null) {
            val msg = curr.message.orEmpty()
            for (pattern in patterns) {
                val match = pattern.find(msg)
                if (match != null) {
                    val seconds = match.groupValues[1].toDoubleOrNull()
                    if (seconds != null && seconds > 0) {
                        return kotlin.math.ceil(seconds * 1000).toLong()
                    }
                }
            }
            curr = curr.cause
        }
        return null
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

        saveChunks(KnowledgeSourceType.NOTE, material.id!!, chunks)
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

        saveChunks(KnowledgeSourceType.NOTE_COMMENT, comment.id!!, listOf(chunk))
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
            figure.medalLevel?.displayName?.let { "Medal: $it" },
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

        saveChunks(KnowledgeSourceType.FIGURE, figure.id!!, listOf(chunk))
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

        saveChunks(KnowledgeSourceType.CHOREOGRAPHY, choreography.id!!, listOf(chunk))
    }

    private fun saveChunks(
        sourceType: KnowledgeSourceType,
        sourceId: UUID,
        chunks: List<KnowledgeChunk>
    ) {
        knowledgeChunkRepository.deleteBySource(sourceType, sourceId)
        for (chunk in chunks) {
            knowledgeChunkRepository.save(chunk.copy(embedding = null))
        }
        triggerProcessingAfterCommit()
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

    override fun getStatus(): KnowledgeIndexStatus {
        val total = knowledgeChunkRepository.countAll().toInt()
        val embedded = knowledgeChunkRepository.countEmbedded(googleAiProperties.embeddingModel).toInt()
        val stale = isStale()
        return KnowledgeIndexStatus(
            totalChunks = total,
            embeddedChunks = embedded,
            isStale = stale
        )
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
        val embeddedChunks = knowledgeChunkRepository.countEmbedded(googleAiProperties.embeddingModel).toInt()
        val duration = System.currentTimeMillis() - start
        log.info("Rebuild chunk extraction completed in {}ms: {} notes, {} comments, {} figures, {} choreographies, {} chunks",
            duration, noteCount, commentCount, figureCount, choreographyCount, totalChunks)

        triggerProcessingAfterCommit()

        return RebuildReport(
            indexedNotes = noteCount,
            indexedComments = commentCount,
            indexedFigures = figureCount,
            indexedChoreographies = choreographyCount,
            totalChunks = totalChunks,
            embeddedChunks = embeddedChunks,
            durationMs = duration
        )
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
