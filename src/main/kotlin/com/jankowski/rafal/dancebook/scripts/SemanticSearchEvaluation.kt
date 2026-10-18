package com.jankowski.rafal.dancebook.scripts

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType
import org.springframework.ai.retry.RetryUtils
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * Semantic search evaluation tool (#347).
 *
 * Compares candidate embedding models and retrieval task types against the evaluation set
 * in `docs/evaluation/semantic-search/`. Never modifies the application index or app database.
 */
object SemanticSearchEvaluation {

    private val log = LoggerFactory.getLogger(SemanticSearchEvaluation::class.java)
    val objectMapper = jacksonObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // ==========================================
    // Data structures
    // ==========================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LabelsFile(
        val confirmed: Boolean = false,
        val status: String? = null,
        val note: String? = null,
        val searches: List<SearchLabel> = emptyList()
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SearchLabel(
        val id: Int,
        val query: String,
        val user: String = "rafal",
        val kind: String? = null,
        val expectedGood: List<String> = emptyList(),
        val expectedWrong: List<String> = emptyList()
    )

    data class EvaluationDocument(
        val id: String,
        val title: String,
        val sourceType: String, // NOTE, FIGURE, NOTE_COMMENT
        val danceType: String? = null,
        val ownerUsername: String? = "rafal",
        val visibility: String = "PUBLIC",
        val content: String
    )

    enum class MatchLabel {
        GOOD, BAD, UNLABELLED
    }

    data class CandidateRankedHit(
        val rank: Int,
        val document: EvaluationDocument,
        val distance: Double,
        val label: MatchLabel
    )

    data class SearchEvaluationMetrics(
        val search: SearchLabel,
        val nearestDistance: Double,
        val tenthDistance: Double,
        val thirtiethDistance: Double,
        val rankedHits: List<CandidateRankedHit>,
        val minGoodDistance: Double?,
        val maxGoodDistance: Double?,
        val minBadDistance: Double?,
        val gap: Double?,
        val isCleanSeparation: Boolean,
        val overlapCount: Int,
        val separationDescription: String
    )

    data class CandidateSummaryMetrics(
        val candidateName: String,
        val candidateDescription: String,
        val status: String, // "Evaluated" or "not tried: <reason>"
        val notTriedReason: String? = null,
        val searchMetrics: List<SearchEvaluationMetrics> = emptyList(),
        val widestGap: Double? = null,
        val suggestedCutoff: Double? = null,
        val noAnswerBehavior: String = "",
        val oneWordBehavior: String = ""
    )

    data class SavedVectors(
        val candidateName: String,
        val dimensions: Int,
        val documents: MutableMap<String, List<Float>> = mutableMapOf(),
        val queries: MutableMap<String, List<Float>> = mutableMapOf()
    )

    // ==========================================
    // Vector Math & Analysis Engine
    // ==========================================

    /**
     * Cosine distance between two vectors: 1.0 - cosine_similarity.
     * Matches pgvector's <=> cosine distance operator.
     */
    fun cosineDistance(u: FloatArray, v: FloatArray): Double {
        require(u.size == v.size) { "Vector dimensions must match: ${u.size} != ${v.size}" }
        var dot = 0.0
        var normU = 0.0
        var normV = 0.0
        for (i in u.indices) {
            val ui = u[i].toDouble()
            val vi = v[i].toDouble()
            dot += ui * vi
            normU += ui * ui
            normV += vi * vi
        }
        if (normU <= 0.0 || normV <= 0.0) return 1.0
        val sim = dot / (sqrt(normU) * sqrt(normV))
        val clampedSim = sim.coerceIn(-1.0, 1.0)
        return 1.0 - clampedSim
    }

    /**
     * Determines whether a document matches the expected good or bad labels for a search.
     */
    fun labelCandidate(doc: EvaluationDocument, search: SearchLabel): MatchLabel {
        // Access rule enforcement: if searching as test_user, rafal's private notes are strictly BAD
        if (search.user == "test_user" && doc.visibility == "PRIVATE" && doc.ownerUsername != "test_user") {
            return MatchLabel.BAD
        }

        // Check wildcard in expectedWrong: everything is wrong
        if (search.expectedWrong.contains("*")) {
            return MatchLabel.BAD
        }

        // Check expected good
        for (pattern in search.expectedGood) {
            if (matchesPattern(doc, pattern)) {
                return MatchLabel.GOOD
            }
        }

        // Check expected wrong
        for (pattern in search.expectedWrong) {
            if (matchesPattern(doc, pattern)) {
                return MatchLabel.BAD
            }
        }

        return MatchLabel.UNLABELLED
    }

    private fun matchesPattern(doc: EvaluationDocument, pattern: String): Boolean {
        val p = pattern.trim().lowercase()
        if (p.isEmpty()) return false
        val title = doc.title.lowercase()
        if (title == p || title.contains(p) || p.contains(title)) {
            return true
        }
        val dance = doc.danceType?.lowercase().orEmpty()
        if (dance.isNotEmpty() && (dance == p || p.contains(dance))) {
            return true
        }
        return false
    }

    /**
     * Checks if a document is visible to the given user under the access rules.
     */
    fun isVisibleToUser(doc: EvaluationDocument, user: String): Boolean {
        if (user == "rafal") return true // Administrator sees all
        if (doc.visibility == "PUBLIC") return true
        return doc.ownerUsername == user
    }

    /**
     * Evaluates a single search query against candidate documents and vectors.
     */
    fun evaluateSearch(
        search: SearchLabel,
        queryVector: FloatArray,
        documents: List<EvaluationDocument>,
        docVectors: Map<String, FloatArray>,
        filterAccess: Boolean = true
    ): SearchEvaluationMetrics {
        // Filter candidates by access rules if specified
        val visibleDocs = if (filterAccess) {
            documents.filter { isVisibleToUser(it, search.user) }
        } else {
            documents
        }

        // Deduplicate multiple chunks per item by retaining the closest chunk distance
        val bestPerItem = mutableMapOf<String, Pair<EvaluationDocument, Double>>()
        for (doc in visibleDocs) {
            val vec = docVectors[doc.id] ?: continue
            val dist = cosineDistance(queryVector, vec)
            val currentBest = bestPerItem[doc.title]
            if (currentBest == null || dist < currentBest.second) {
                bestPerItem[doc.title] = doc to dist
            }
        }

        val sortedItems = bestPerItem.values.sortedBy { it.second }
        val rankedHits = sortedItems.mapIndexed { index, (doc, dist) ->
            CandidateRankedHit(
                rank = index + 1,
                document = doc,
                distance = dist,
                label = labelCandidate(doc, search)
            )
        }

        val nearest = rankedHits.firstOrNull()?.distance ?: Double.NaN
        val tenth = rankedHits.getOrNull(9)?.distance ?: rankedHits.lastOrNull()?.distance ?: Double.NaN
        val thirtieth = rankedHits.getOrNull(29)?.distance ?: rankedHits.lastOrNull()?.distance ?: Double.NaN

        val goodHits = rankedHits.filter { it.label == MatchLabel.GOOD }
        val badHits = rankedHits.filter { it.label == MatchLabel.BAD }

        val minGood = goodHits.minOfOrNull { it.distance }
        val maxGood = goodHits.maxOfOrNull { it.distance }
        val minBad = badHits.minOfOrNull { it.distance }

        val isClean: Boolean
        val gap: Double?
        val overlapCount: Int
        val desc: String

        if (goodHits.isNotEmpty() && minBad != null && maxGood != null) {
            gap = minBad - maxGood
            if (gap > 0.0) {
                isClean = true
                overlapCount = 0
                desc = "Clean separation (gap: +%.3f, cutoff in [%.3f, %.3f])".format(gap, maxGood, minBad)
            } else {
                isClean = false
                overlapCount = badHits.count { it.distance <= maxGood }
                desc = "Overlap of %.3f (%d bad hits before last good hit; min bad: %.3f, max good: %.3f)".format(
                    -gap, overlapCount, minBad, maxGood
                )
            }
        } else if (goodHits.isEmpty() && minBad != null) {
            isClean = true
            gap = null
            overlapCount = 0
            desc = "No good candidates (nearest noise at %.3f)".format(minBad)
        } else if (goodHits.isNotEmpty() && minBad == null) {
            isClean = true
            gap = null
            overlapCount = 0
            desc = "All candidates good up to %.3f".format(maxGood ?: Double.NaN)
        } else {
            isClean = true
            gap = null
            overlapCount = 0
            desc = "No labelled candidates"
        }

        return SearchEvaluationMetrics(
            search = search,
            nearestDistance = nearest,
            tenthDistance = tenth,
            thirtiethDistance = thirtieth,
            rankedHits = rankedHits,
            minGoodDistance = minGood,
            maxGoodDistance = maxGood,
            minBadDistance = minBad,
            gap = gap,
            isCleanSeparation = isClean,
            overlapCount = overlapCount,
            separationDescription = desc
        )
    }

    /**
     * Computes candidate-wide summary metrics: widest gap, suggested cutoff, and query behaviors.
     */
    fun summarizeCandidate(
        candidateName: String,
        candidateDescription: String,
        searchMetrics: List<SearchEvaluationMetrics>
    ): CandidateSummaryMetrics {
        // Collect all good and bad distances across searches
        val allGood = searchMetrics.flatMap { m -> m.rankedHits.filter { it.label == MatchLabel.GOOD }.map { it.distance } }
        val allBad = searchMetrics.flatMap { m -> m.rankedHits.filter { it.label == MatchLabel.BAD }.map { it.distance } }

        // Find optimal cutoff that maximizes good hits retained while minimizing bad hits admitted
        var bestCutoff = 0.42
        var bestScore = -1.0
        var widestGap: Double? = null

        if (allGood.isNotEmpty() && allBad.isNotEmpty()) {
            val maxGood = allGood.maxOrNull() ?: 0.42
            val minBad = allBad.minOrNull() ?: 0.42
            if (minBad > maxGood) {
                widestGap = minBad - maxGood
                bestCutoff = (minBad + maxGood) / 2.0
            } else {
                // Find threshold that maximizes (good_below - bad_below)
                val candidateThresholds = (allGood + allBad).sorted().distinct()
                for (th in candidateThresholds) {
                    val goodKept = allGood.count { it <= th }
                    val badKept = allBad.count { it <= th }
                    val score = goodKept.toDouble() - badKept.toDouble()
                    if (score > bestScore) {
                        bestScore = score
                        bestCutoff = th
                    }
                }
                widestGap = minBad - maxGood // negative or zero indicates overlap
            }
        }

        // No-answer searches behavior (search 7: swimming pool, search 4: Latin if treated as no answers)
        val noAnswerMetrics = searchMetrics.filter { it.search.query == "swimming pool opening hours" || it.search.expectedGood.isEmpty() }
        val noAnswerDesc = noAnswerMetrics.joinToString("; ") { m ->
            val nearest = m.nearestDistance
            val rejected = nearest > bestCutoff
            "'${m.search.query}': nearest at %.3f (%s by cutoff %.2f)".format(
                nearest, if (rejected) "cleanly rejected" else "admitted as false positive", bestCutoff
            )
        }

        // One-word searches behavior ("hips", "rise", "Latin")
        val oneWordMetrics = searchMetrics.filter { it.search.query in listOf("hips", "rise", "Latin") }
        val oneWordDesc = oneWordMetrics.joinToString("; ") { m ->
            val nearest = m.nearestDistance
            val goodDist = m.minGoodDistance
            "'${m.search.query}': nearest at %.3f (good at %s)".format(
                nearest, goodDist?.let { "%.3f".format(it) } ?: "none"
            )
        }

        return CandidateSummaryMetrics(
            candidateName = candidateName,
            candidateDescription = candidateDescription,
            status = "Evaluated",
            searchMetrics = searchMetrics,
            widestGap = widestGap,
            suggestedCutoff = bestCutoff,
            noAnswerBehavior = noAnswerDesc,
            oneWordBehavior = oneWordDesc
        )
    }

    // ==========================================
    // Vector Persistence & Cache
    // ==========================================

    fun vectorCacheFile(baseDir: File, candidateName: String): File {
        return File(baseDir, "vectors-${candidateName.replace('/', '-')}.json")
    }

    fun loadVectors(file: File): SavedVectors? {
        if (!file.exists()) return null
        return try {
            objectMapper.readValue<SavedVectors>(file)
        } catch (e: Exception) {
            log.warn("Failed to read cached vectors from {}: {}", file.absolutePath, e.message)
            null
        }
    }

    fun saveVectors(file: File, vectors: SavedVectors) {
        file.parentFile?.mkdirs()
        objectMapper.writeValue(file, vectors)
    }

    // ==========================================
    // Document Loading (from DB or seed.sql)
    // ==========================================

    fun loadEvaluationDocuments(seedSqlFile: File, dbUrl: String?, dbUser: String?, dbPass: String?): List<EvaluationDocument> {
        if (!dbUrl.isNullOrBlank() && !dbUser.isNullOrBlank()) {
            try {
                return loadFromDatabase(dbUrl, dbUser, dbPass.orEmpty())
            } catch (e: Exception) {
                log.warn("Database connection failed ({}); falling back to parsing seed.sql", e.message)
            }
        }
        return parseDocumentsFromSeedSql(seedSqlFile)
    }

    private fun loadFromDatabase(url: String, user: String, pass: String): List<EvaluationDocument> {
        val docs = mutableListOf<EvaluationDocument>()
        DriverManager.getConnection(url, user, pass).use { conn ->
            // 1. Notes
            val noteSql = """
                SELECT m.id, m.name, m.description, t.name as dance_name, u.username as owner_name, m.visibility
                FROM material m
                LEFT JOIN dance_type t ON t.id = m.dance_type_id
                LEFT JOIN app_user u ON u.id = m.owner_id
            """.trimIndent()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(noteSql)
                while (rs.next()) {
                    val id = rs.getString("id")
                    val name = rs.getString("name")
                    val desc = rs.getString("description").orEmpty()
                    val dance = rs.getString("dance_name")
                    val owner = rs.getString("owner_name")
                    val vis = rs.getString("visibility") ?: "PUBLIC"
                    val content = listOfNotNull(
                        "Note: $name",
                        dance?.let { "Dance: $it" },
                        desc.replace(Regex("<[^>]*>"), " ").trim()
                    ).joinToString("\n")
                    docs.add(EvaluationDocument(id, name, "NOTE", dance, owner, vis, content))
                }
            }

            // 2. Figures with notes
            val figSql = """
                SELECT f.id, f.name, f.notes, f.starting_position, f.ending_position, t.name as dance_name
                FROM dance_figure f
                JOIN dance_type t ON t.id = f.dance_type_id
                WHERE f.notes IS NOT NULL
            """.trimIndent()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(figSql)
                while (rs.next()) {
                    val id = rs.getString("id")
                    val name = rs.getString("name")
                    val notes = rs.getString("notes").orEmpty()
                    val dance = rs.getString("dance_name")
                    val sp = rs.getString("starting_position")
                    val ep = rs.getString("ending_position")
                    val content = listOfNotNull(
                        "Figure: $name",
                        dance?.let { "Dance: $it" },
                        sp?.let { "Starts: $it" },
                        ep?.let { "Ends: $it" },
                        notes.takeIf { it.isNotBlank() }?.let { "Notes: $it" }
                    ).joinToString("\n")
                    docs.add(EvaluationDocument(id, "$name ($dance)", "FIGURE", dance, "rafal", "PUBLIC", content))
                }
            }
        }
        return docs
    }

    /**
     * Fallback parser extracting the evaluation notes and figures directly from seed.sql.
     */
    fun parseDocumentsFromSeedSql(seedSqlFile: File): List<EvaluationDocument> {
        val docs = mutableListOf<EvaluationDocument>()
        if (!seedSqlFile.exists()) return docs
        val sql = seedSqlFile.readText()

        // 1. Parse seed_figure technique notes
        // Format: ($q$Waltz$q$,$q$Natural Turn$q$,$q$Facing Diagonal Wall$q$,$q$Backing Diagonal Centre$q$,$q$...$q$)
        val qTag = "$" + "q" + "$"
        val figPattern = """\(\Q$qTag\E([^$]+)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]+)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]*)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]*)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]*)\Q$qTag\E\)"""
        val figRegex = Regex(figPattern)
        for (match in figRegex.findAll(sql)) {
            val dance = match.groupValues[1]
            val name = match.groupValues[2]
            val sp = match.groupValues[3]
            val ep = match.groupValues[4]
            val notes = match.groupValues[5]
            val id = "fig-" + (dance + "-" + name).lowercase().replace(' ', '-')
            val content = listOfNotNull(
                "Figure: $name",
                "Dance: $dance",
                sp.takeIf { it.isNotBlank() }?.let { "Starts: $it" },
                ep.takeIf { it.isNotBlank() }?.let { "Ends: $it" },
                notes.takeIf { it.isNotBlank() }?.let { "Notes: $it" }
            ).joinToString("\n")
            docs.add(EvaluationDocument(id, "$name ($dance)", "FIGURE", dance, "rafal", "PUBLIC", content))
        }

        // 2. Parse materials (Notes)
        // Format: ($q$uuid$q$,$q$title$q$,$q$descr$q$,$q$dance$q$,$q$owner$q$,$q$vis$q$)
        val matPattern = """\(\Q$qTag\E([a-f0-9\-]+)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]+)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]*)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]*)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]*)\Q$qTag\E\s*,\s*\Q$qTag\E([^$]*)\Q$qTag\E\)"""
        val matRegex = Regex(matPattern)
        for (match in matRegex.findAll(sql)) {
            val id = match.groupValues[1]
            val name = match.groupValues[2]
            val rawDesc = match.groupValues[3]
            val dance = match.groupValues[4].takeIf { it.isNotBlank() }
            val owner = match.groupValues[5].ifBlank { "rafal" }
            val vis = match.groupValues[6].ifBlank { "PUBLIC" }
            val plainDesc = rawDesc.replace(Regex("<[^>]*>"), " ").trim()
            val content = listOfNotNull(
                "Note: $name",
                dance?.let { "Dance: $it" },
                plainDesc
            ).joinToString("\n")
            docs.add(EvaluationDocument(id, name, "NOTE", dance, owner, vis, content))
        }

        return docs
    }

    // ==========================================
    // Rate Limiter
    // ==========================================

    class RateLimiter(requestsPerMinute: Int) {
        private val minIntervalMs = (60_000.0 / requestsPerMinute).toLong() + 20L
        private var lastRequestTime = 0L

        @Synchronized
        fun acquire() {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < minIntervalMs) {
                Thread.sleep(minIntervalMs - elapsed)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }

    // ==========================================
    // Candidate Provider Implementations
    // ==========================================

    interface CandidateEmbedder {
        val name: String
        val description: String
        val dimensions: Int
        fun embedDocument(text: String): FloatArray
        fun embedQuery(text: String): FloatArray
    }

    class GoogleAiCandidate(
        override val name: String,
        override val description: String,
        private val apiKey: String,
        private val modelName: String,
        override val dimensions: Int,
        private val docTaskType: TaskType? = null,
        private val queryTaskType: TaskType? = null,
        private val rateLimiter: RateLimiter
    ) : CandidateEmbedder {

        private val docModel: GoogleGenAiTextEmbeddingModel
        private val queryModel: GoogleGenAiTextEmbeddingModel

        init {
            val details = GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build()

            val docOpts = GoogleGenAiTextEmbeddingOptions.builder()
                .model(modelName)
                .dimensions(dimensions)
                .apply { if (docTaskType != null) taskType(docTaskType) }
                .build()
            docModel = GoogleGenAiTextEmbeddingModel(details, docOpts, RetryUtils.SHORT_RETRY_TEMPLATE)

            val queryOpts = GoogleGenAiTextEmbeddingOptions.builder()
                .model(modelName)
                .dimensions(dimensions)
                .apply { if (queryTaskType != null) taskType(queryTaskType) }
                .build()
            queryModel = GoogleGenAiTextEmbeddingModel(details, queryOpts, RetryUtils.SHORT_RETRY_TEMPLATE)
        }

        override fun embedDocument(text: String): FloatArray {
            rateLimiter.acquire()
            return docModel.embed(text)
        }

        override fun embedQuery(text: String): FloatArray {
            rateLimiter.acquire()
            return queryModel.embed(text)
        }
    }

    class OllamaCandidate(
        override val name: String,
        override val description: String,
        private val apiKey: String,
        private val baseUrl: String,
        private val modelName: String,
        override val dimensions: Int = 768,
        private val rateLimiter: RateLimiter
    ) : CandidateEmbedder {

        private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        override fun embedDocument(text: String): FloatArray = embed(text)
        override fun embedQuery(text: String): FloatArray = embed(text)

        private fun embed(text: String): FloatArray {
            rateLimiter.acquire()
            val url = URI.create(baseUrl.trimEnd('/') + "/api/embed")
            val payload = objectMapper.writeValueAsString(mapOf("model" to modelName, "input" to text))
            val req = HttpRequest.newBuilder()
                .uri(url)
                .header("Content-Type", "application/json")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()

            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() != 200) {
                throw RuntimeException("Ollama API failed (${resp.statusCode()}): ${resp.body()}")
            }
            val json = objectMapper.readTree(resp.body())
            val embeddings = json.get("embeddings")?.get(0)
                ?: throw RuntimeException("No embedding returned by Ollama")
            val result = FloatArray(embeddings.size())
            for (i in 0 until embeddings.size()) {
                result[i] = embeddings.get(i).floatValue()
            }
            return result
        }
    }

    // ==========================================
    // Markdown Report Generation
    // ==========================================

    fun generateMarkdownReport(
        labelsFile: LabelsFile,
        candidates: List<CandidateSummaryMetrics>
    ): String {
        val sb = StringBuilder()
        sb.append("# Semantic Search Model & Task Type Comparison Report\n\n")
        sb.append("Generated on: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}\n")
        sb.append("Labels Status: **${if (labelsFile.confirmed) "Confirmed by Owner" else "Unconfirmed Proposal"}**\n\n")

        sb.append("## Overview\n\n")
        sb.append("This report evaluates candidate embedding models and task types against the 10 example searches in `docs/evaluation/semantic-search/` ")
        sb.append("to determine separation between expected good and wrong matches, optimal similarity cutoffs, and query behaviors.\n\n")

        sb.append("## Candidate Summary\n\n")
        sb.append("| Candidate | Status | Widest Gap | Suggested Cutoff | No-Answer Queries | One-Word Queries |\n")
        sb.append("|---|---|---|---|---|---|\n")

        for (c in candidates) {
            val gapStr = if (c.widestGap != null) {
                if (c.widestGap > 0) "+%.3f (Clean)".format(c.widestGap) else "%.3f (Overlap)".format(c.widestGap)
            } else "N/A"
            val cutoffStr = c.suggestedCutoff?.let { "%.2f".format(it) } ?: "N/A"
            val statusDisplay = if (c.notTriedReason != null) "not tried: ${c.notTriedReason}" else c.status
            sb.append("| **${c.candidateName}**<br>_${c.candidateDescription}_ | $statusDisplay | $gapStr | $cutoffStr | ${c.noAnswerBehavior} | ${c.oneWordBehavior} |\n")
        }
        sb.append("\n")

        // Detailed Per-Candidate Search Results
        for (c in candidates) {
            if (c.searchMetrics.isEmpty()) continue
            sb.append("## Candidate: ${c.candidateName}\n\n")
            sb.append("_${c.candidateDescription}_\n\n")

            sb.append("### Search Distance Summary\n\n")
            sb.append("| # | Search | Nearest | 10th | 30th | Good/Bad Separation |\n")
            sb.append("|---|---|---|---|---|---|\n")
            for (m in c.searchMetrics) {
                sb.append("| ${m.search.id} | `${m.search.query}` | %.3f | %.3f | %.3f | %s |\n".format(
                    m.nearestDistance, m.tenthDistance, m.thirtiethDistance, m.separationDescription
                ))
            }
            sb.append("\n")

            sb.append("### Ranked Candidates per Search\n\n")
            for (m in c.searchMetrics) {
                sb.append("#### Search #${m.search.id}: `${m.search.query}` (${m.search.user})\n\n")
                sb.append("- Kind: ${m.search.kind.orEmpty()}\n")
                sb.append("- Separation: ${m.separationDescription}\n\n")
                sb.append("| Rank | Distance | Candidate Title | Type | Label |\n")
                sb.append("|---|---|---|---|---|\n")
                for (hit in m.rankedHits.take(30)) {
                    val labelBadge = when (hit.label) {
                        MatchLabel.GOOD -> "**GOOD**"
                        MatchLabel.BAD -> "BAD"
                        MatchLabel.UNLABELLED -> "—"
                    }
                    sb.append("| ${hit.rank} | %.3f | %s | %s | %s |\n".format(
                        hit.distance, hit.document.title, hit.document.sourceType, labelBadge
                    ))
                }
                sb.append("\n")
            }
        }

        return sb.toString()
    }

    data class CandidateDefinition(
        val name: String,
        val description: String,
        val buildEmbedder: () -> CandidateEmbedder?,
        val fallbackReason: String? = null
    )

    fun cleanErrorMessage(e: Throwable): String {
        var curr: Throwable? = e
        var msg = curr?.message
        while (curr != null && (msg == null || msg.isBlank())) {
            curr = curr.cause
            if (curr?.message?.isNotBlank() == true) {
                msg = curr.message
            }
        }
        val raw = msg ?: e.javaClass.simpleName
        return raw
            .replace(Regex("""(?i)(?:key|api_?key|token|bearer)[=:\s]+[A-Za-z0-9_\-\.]+"""), "[REDACTED]")
            .replace(Regex("""AIza[0-9A-Za-z\-_]{35}"""), "[REDACTED]")
            .trim()
    }

    fun evaluateCandidate(
        cDef: CandidateDefinition,
        cacheFile: File,
        documents: List<EvaluationDocument>,
        labels: LabelsFile
    ): CandidateSummaryMetrics {
        val cachedVectors = loadVectors(cacheFile)

        if (cachedVectors == null && cDef.fallbackReason != null) {
            println("Skipping candidate '${cDef.name}': not tried (${cDef.fallbackReason})")
            return CandidateSummaryMetrics(
                candidateName = cDef.name,
                candidateDescription = cDef.description,
                status = "not tried: ${cDef.fallbackReason}",
                notTriedReason = cDef.fallbackReason
            )
        }

        val embedder = try {
            cDef.buildEmbedder()
        } catch (e: Throwable) {
            val reason = cleanErrorMessage(e)
            println("Failed to initialize candidate '${cDef.name}': $reason")
            return CandidateSummaryMetrics(
                candidateName = cDef.name,
                candidateDescription = cDef.description,
                status = "not tried: $reason",
                notTriedReason = reason
            )
        }

        if (cachedVectors == null && embedder == null) {
            val reason = cDef.fallbackReason ?: "provider unavailable"
            println("Skipping candidate '${cDef.name}': not tried ($reason)")
            return CandidateSummaryMetrics(
                candidateName = cDef.name,
                candidateDescription = cDef.description,
                status = "not tried: $reason",
                notTriedReason = reason
            )
        }

        val vectorsObj = cachedVectors ?: SavedVectors(
            candidateName = cDef.name,
            dimensions = embedder?.dimensions ?: 0
        )

        // Check missing documents
        val missingDocs = documents.filter { it.id !in vectorsObj.documents }
        val missingQueries = labels.searches.filter { it.query !in vectorsObj.queries }

        if (missingDocs.isNotEmpty() || missingQueries.isNotEmpty()) {
            if (embedder == null) {
                val reason = "incomplete cache and missing API key"
                println("Cannot evaluate candidate '${cDef.name}': $reason")
                return CandidateSummaryMetrics(
                    candidateName = cDef.name,
                    candidateDescription = cDef.description,
                    status = "not tried: $reason",
                    notTriedReason = reason
                )
            }

            var failureReason: String? = null
            try {
                if (missingDocs.isNotEmpty()) {
                    println("Embedding ${missingDocs.size} documents for candidate '${cDef.name}'...")
                    for ((idx, doc) in missingDocs.withIndex()) {
                        print("\rEmbedding documents: ${idx + 1}/${missingDocs.size}")
                        try {
                            val vec = embedder.embedDocument(doc.content)
                            vectorsObj.documents[doc.id] = vec.toList()
                        } catch (t: Throwable) {
                            failureReason = cleanErrorMessage(t)
                            println("\nError embedding document '${doc.title}': $failureReason")
                            break
                        }
                    }
                    println()
                }

                if (failureReason == null && missingQueries.isNotEmpty()) {
                    println("Embedding ${missingQueries.size} queries for candidate '${cDef.name}'...")
                    for ((idx, s) in missingQueries.withIndex()) {
                        print("\rEmbedding queries: ${idx + 1}/${missingQueries.size}")
                        try {
                            val vec = embedder.embedQuery(s.query)
                            vectorsObj.queries[s.query] = vec.toList()
                        } catch (t: Throwable) {
                            failureReason = cleanErrorMessage(t)
                            println("\nError embedding query '${s.query}': $failureReason")
                            break
                        }
                    }
                    println()
                }
            } finally {
                // Vectors embedded before the failure are saved to that candidate's cache file, so a re-run resumes from where it stopped.
                if (vectorsObj.documents.isNotEmpty() || vectorsObj.queries.isNotEmpty()) {
                    try {
                        saveVectors(cacheFile, vectorsObj)
                        println("Saved ${vectorsObj.documents.size} document vectors and ${vectorsObj.queries.size} query vectors to ${cacheFile.name}")
                    } catch (e: Exception) {
                        println("Failed to save partial vectors cache: ${cleanErrorMessage(e)}")
                    }
                }
            }

            if (failureReason != null) {
                println("Candidate '${cDef.name}' marked not tried due to embedding failure: $failureReason")
                return CandidateSummaryMetrics(
                    candidateName = cDef.name,
                    candidateDescription = cDef.description,
                    status = "not tried: $failureReason",
                    notTriedReason = failureReason
                )
            }
        } else {
            println("Using cached vectors for candidate '${cDef.name}' (${vectorsObj.documents.size} docs, ${vectorsObj.queries.size} queries)")
        }

        // Run evaluation
        val docVectors = vectorsObj.documents.mapValues { it.value.toFloatArray() }
        val queryVectors = vectorsObj.queries.mapValues { it.value.toFloatArray() }

        val searchMetrics = labels.searches.mapNotNull { search ->
            val qVec = queryVectors[search.query] ?: return@mapNotNull null
            evaluateSearch(search, qVec, documents, docVectors)
        }

        return summarizeCandidate(cDef.name, cDef.description, searchMetrics)
    }

    fun runEvaluation(
        candidates: List<CandidateDefinition>,
        evalDir: File,
        documents: List<EvaluationDocument>,
        labels: LabelsFile
    ): List<CandidateSummaryMetrics> {
        return candidates.map { cDef ->
            val cacheFile = vectorCacheFile(evalDir, cDef.name)
            evaluateCandidate(cDef, cacheFile, documents, labels)
        }
    }
}

fun main(args: Array<String>) {
    var selectedCandidate: String? = null
    var argIdx = 0
    while (argIdx < args.size) {
        if (args[argIdx] == "--candidate" || args[argIdx] == "-c") {
            selectedCandidate = args.getOrNull(argIdx + 1)
            argIdx += 2
        } else {
            argIdx++
        }
    }

    val evalDir = File("docs/evaluation/semantic-search")
    val labelsFile = File(evalDir, "labels.json")
    val seedSqlFile = File(evalDir, "seed.sql")
    val reportFile = File(evalDir, "REPORT.md")

    if (!labelsFile.exists()) {
        System.err.println("Error: labels.json not found at ${labelsFile.absolutePath}")
        System.exit(1)
    }

    val labels: SemanticSearchEvaluation.LabelsFile = try {
        SemanticSearchEvaluation.objectMapper.readValue(labelsFile, SemanticSearchEvaluation.LabelsFile::class.java)
    } catch (e: Exception) {
        System.err.println("Error reading labels.json: ${e.message}")
        return
    }

    val googleApiKey = System.getenv("GOOGLE_AI_API_KEY") ?: System.getProperty("google.ai.api-key").orEmpty()
    val ollamaApiKey = System.getenv("OLLAMA_API_KEY") ?: System.getProperty("ollama.api-key").orEmpty()
    val ollamaBaseUrl = System.getenv("OLLAMA_BASE_URL") ?: "https://ollama.com"

    if (googleApiKey.isBlank()) {
        println("Note: GOOGLE_AI_API_KEY is not set. Google AI candidates without cached vectors will be marked 'not tried'.")
    }

    val dbUrl = System.getenv("DATABASE_URL")
    val dbUser = System.getenv("DATABASE_USERNAME")
    val dbPass = System.getenv("DATABASE_PASSWORD")

    println("Loading evaluation documents...")
    val documents = SemanticSearchEvaluation.loadEvaluationDocuments(seedSqlFile, dbUrl, dbUser, dbPass)
    println("Loaded ${documents.size} evaluation documents.")

    val rateLimiter = SemanticSearchEvaluation.RateLimiter(requestsPerMinute = 90)

    // Candidates configuration:
    // 1. Current setup: gemini-embedding-001 (768 dims, no task type)
    // 2. gemini-embedding-001 with task types (RETRIEVAL_QUERY / RETRIEVAL_DOCUMENT)
    // 3. gemini-embedding-2-preview (3072 dims)
    // 4. embeddinggemma via Ollama Cloud (marked not tried if unconfigured)
    val candidateDefs = listOf(
        SemanticSearchEvaluation.CandidateDefinition(
            name = "gemini-embedding-001",
            description = "Current setup: gemini-embedding-001 (768 dimensions, no task type)",
            buildEmbedder = {
                if (googleApiKey.isBlank()) null
                else SemanticSearchEvaluation.GoogleAiCandidate(
                    name = "gemini-embedding-001",
                    description = "gemini-embedding-001 (768 dimensions, no task type)",
                    apiKey = googleApiKey,
                    modelName = "gemini-embedding-001",
                    dimensions = 768,
                    rateLimiter = rateLimiter
                )
            },
            fallbackReason = if (googleApiKey.isBlank()) "no GOOGLE_AI_API_KEY configured" else null
        ),
        SemanticSearchEvaluation.CandidateDefinition(
            name = "gemini-embedding-001-task-types",
            description = "gemini-embedding-001 with RETRIEVAL_QUERY and RETRIEVAL_DOCUMENT task types (768 dimensions)",
            buildEmbedder = {
                if (googleApiKey.isBlank()) null
                else SemanticSearchEvaluation.GoogleAiCandidate(
                    name = "gemini-embedding-001-task-types",
                    description = "gemini-embedding-001 with query/document task types (768 dimensions)",
                    apiKey = googleApiKey,
                    modelName = "gemini-embedding-001",
                    dimensions = 768,
                    docTaskType = TaskType.RETRIEVAL_DOCUMENT,
                    queryTaskType = TaskType.RETRIEVAL_QUERY,
                    rateLimiter = rateLimiter
                )
            },
            fallbackReason = if (googleApiKey.isBlank()) "no GOOGLE_AI_API_KEY configured" else null
        ),
        SemanticSearchEvaluation.CandidateDefinition(
            name = "gemini-embedding-2-preview",
            description = "Gemini Embedding 2: gemini-embedding-2-preview (3072 dimensions)",
            buildEmbedder = {
                if (googleApiKey.isBlank()) null
                else SemanticSearchEvaluation.GoogleAiCandidate(
                    name = "gemini-embedding-2-preview",
                    description = "Gemini Embedding 2 preview (3072 dimensions)",
                    apiKey = googleApiKey,
                    modelName = "gemini-embedding-2-preview",
                    dimensions = 3072,
                    docTaskType = TaskType.RETRIEVAL_DOCUMENT,
                    queryTaskType = TaskType.RETRIEVAL_QUERY,
                    rateLimiter = rateLimiter
                )
            },
            fallbackReason = if (googleApiKey.isBlank()) "no GOOGLE_AI_API_KEY configured" else null
        ),
        SemanticSearchEvaluation.CandidateDefinition(
            name = "embedding-gemma",
            description = "EmbeddingGemma via Ollama Cloud (768 dimensions)",
            buildEmbedder = {
                if (ollamaApiKey.isBlank()) null
                else SemanticSearchEvaluation.OllamaCandidate(
                    name = "embedding-gemma",
                    description = "EmbeddingGemma via Ollama Cloud (768 dimensions)",
                    apiKey = ollamaApiKey,
                    baseUrl = ollamaBaseUrl,
                    modelName = "embeddinggemma",
                    rateLimiter = rateLimiter
                )
            },
            fallbackReason = if (ollamaApiKey.isBlank()) "no OLLAMA_API_KEY configured" else null
        )
    )

    val activeCandidateDefs = if (selectedCandidate != null) {
        candidateDefs.filter { it.name.equals(selectedCandidate, ignoreCase = true) }
    } else {
        candidateDefs
    }

    val results = SemanticSearchEvaluation.runEvaluation(activeCandidateDefs, evalDir, documents, labels)

    val reportMarkdown = SemanticSearchEvaluation.generateMarkdownReport(labels, results)
    reportFile.writeText(reportMarkdown)
    println("\nEvaluation completed. Report written to: ${reportFile.absolutePath}")
}
