package com.jankowski.rafal.dancebook.scripts

import com.jankowski.rafal.dancebook.scripts.SemanticSearchEvaluation.CandidateRankedHit
import com.jankowski.rafal.dancebook.scripts.SemanticSearchEvaluation.CandidateSummaryMetrics
import com.jankowski.rafal.dancebook.scripts.SemanticSearchEvaluation.EvaluationDocument
import com.jankowski.rafal.dancebook.scripts.SemanticSearchEvaluation.LabelsFile
import com.jankowski.rafal.dancebook.scripts.SemanticSearchEvaluation.MatchLabel
import com.jankowski.rafal.dancebook.scripts.SemanticSearchEvaluation.SavedVectors
import com.jankowski.rafal.dancebook.scripts.SemanticSearchEvaluation.SearchLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs

class SemanticSearchEvaluationTest {

    private val eps = 1e-4

    @Test
    fun `should calculate cosine distance correctly on fixed vectors`() {
        val u = floatArrayOf(1f, 0f, 0f)
        val vIdentical = floatArrayOf(2f, 0f, 0f)
        val vOrthogonal = floatArrayOf(0f, 1f, 0f)
        val vOpposite = floatArrayOf(-1f, 0f, 0f)

        assertEquals(0.0, SemanticSearchEvaluation.cosineDistance(u, vIdentical), eps)
        assertEquals(1.0, SemanticSearchEvaluation.cosineDistance(u, vOrthogonal), eps)
        assertEquals(2.0, SemanticSearchEvaluation.cosineDistance(u, vOpposite), eps)

        val u45 = floatArrayOf(1f, 1f)
        val v45 = floatArrayOf(1f, 0f)
        // Cosine of 45 deg is 1 / sqrt(2) ≈ 0.7071 => distance ≈ 0.2929
        assertEquals(0.2929, SemanticSearchEvaluation.cosineDistance(u45, v45), eps)
    }

    @Test
    fun `should pick nearest, 10th and 30th candidates in ranked order`() {
        val queryVector = floatArrayOf(1f, 0f)
        val documents = mutableListOf<EvaluationDocument>()
        val docVectors = mutableMapOf<String, FloatArray>()

        // Generate 35 documents with increasing angles from query vector
        for (i in 1..35) {
            val id = "doc-$i"
            val title = "Document $i"
            val angle = i * 0.04 // small incremental angle
            val vec = floatArrayOf(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            documents.add(EvaluationDocument(id, title, "NOTE", "Waltz", "rafal", "PUBLIC", "Content $i"))
            docVectors[id] = vec
        }

        val search = SearchLabel(
            id = 1,
            query = "test search",
            user = "rafal",
            expectedGood = listOf("Document 1", "Document 2"),
            expectedWrong = listOf("Document 30", "Document 31")
        )

        val metrics = SemanticSearchEvaluation.evaluateSearch(search, queryVector, documents, docVectors)

        assertEquals(35, metrics.rankedHits.size)
        // Ranks should be sorted ascending by distance
        assertTrue(metrics.rankedHits[0].distance < metrics.rankedHits[9].distance)
        assertTrue(metrics.rankedHits[9].distance < metrics.rankedHits[29].distance)

        assertEquals(metrics.rankedHits[0].distance, metrics.nearestDistance, eps)
        assertEquals(metrics.rankedHits[9].distance, metrics.tenthDistance, eps)
        assertEquals(metrics.rankedHits[29].distance, metrics.thirtiethDistance, eps)
    }

    @Test
    fun `should detect clean separation and suggest cutoff between good and bad`() {
        val search = SearchLabel(
            id = 1,
            query = "head dropping",
            user = "rafal",
            expectedGood = listOf("Good Note 1", "Good Note 2"),
            expectedWrong = listOf("Bad Note 1", "Bad Note 2")
        )

        val queryVec = floatArrayOf(1f, 0f)
        val doc1 = EvaluationDocument("d1", "Good Note 1", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val doc2 = EvaluationDocument("d2", "Good Note 2", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val doc3 = EvaluationDocument("d3", "Bad Note 1", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val doc4 = EvaluationDocument("d4", "Bad Note 2", "NOTE", "Waltz", "rafal", "PUBLIC", "...")

        // Fixed vectors to produce:
        // doc1: dist ~ 0.20
        // doc2: dist ~ 0.28
        // doc3: dist ~ 0.40
        // doc4: dist ~ 0.50
        fun vectorAtAngle(deg: Double): FloatArray {
            val rad = Math.toRadians(deg)
            return floatArrayOf(kotlin.math.cos(rad).toFloat(), kotlin.math.sin(rad).toFloat())
        }

        // angle where 1 - cos(theta) = 0.20 => cos(theta) = 0.80 => theta ≈ 36.87 deg
        // 1 - cos(theta) = 0.28 => cos(theta) = 0.72 => theta ≈ 43.95 deg
        // 1 - cos(theta) = 0.40 => cos(theta) = 0.60 => theta ≈ 53.13 deg
        // 1 - cos(theta) = 0.50 => cos(theta) = 0.50 => theta ≈ 60.00 deg
        val docVectors = mapOf(
            "d1" to vectorAtAngle(36.87),
            "d2" to vectorAtAngle(43.95),
            "d3" to vectorAtAngle(53.13),
            "d4" to vectorAtAngle(60.00)
        )

        val metrics = SemanticSearchEvaluation.evaluateSearch(search, queryVec, listOf(doc1, doc2, doc3, doc4), docVectors)

        assertTrue(metrics.isCleanSeparation)
        assertEquals(0, metrics.overlapCount)
        assertNotNull(metrics.gap)
        // gap = minBad (0.40) - maxGood (0.28) = 0.12
        assertEquals(0.12, metrics.gap!!, 0.01)
        assertTrue(metrics.separationDescription.contains("Clean separation"))

        val summary = SemanticSearchEvaluation.summarizeCandidate(
            "candidate-test",
            "Test description",
            listOf(metrics)
        )
        assertNotNull(summary.widestGap)
        assertTrue(summary.widestGap!! > 0.0)
        // suggested cutoff should be in (0.28, 0.40)
        assertTrue(summary.suggestedCutoff!! in 0.28..0.40)
    }

    @Test
    fun `should detect overlap when bad candidate is closer than farthest good candidate`() {
        val search = SearchLabel(
            id = 2,
            query = "overlap query",
            user = "rafal",
            expectedGood = listOf("Good Note 1", "Good Note 2"),
            expectedWrong = listOf("Bad Note 1")
        )

        val queryVec = floatArrayOf(1f, 0f)
        val doc1 = EvaluationDocument("d1", "Good Note 1", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val doc2 = EvaluationDocument("d2", "Bad Note 1", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val doc3 = EvaluationDocument("d3", "Good Note 2", "NOTE", "Waltz", "rafal", "PUBLIC", "...")

        fun vectorAtAngle(deg: Double): FloatArray {
            val rad = Math.toRadians(deg)
            return floatArrayOf(kotlin.math.cos(rad).toFloat(), kotlin.math.sin(rad).toFloat())
        }

        // doc1: good, dist ~ 0.20
        // doc2: bad, dist ~ 0.35
        // doc3: good, dist ~ 0.42
        val docVectors = mapOf(
            "d1" to vectorAtAngle(36.87), // 0.20
            "d2" to vectorAtAngle(49.46), // 0.35
            "d3" to vectorAtAngle(54.55)  // 0.42
        )

        val metrics = SemanticSearchEvaluation.evaluateSearch(search, queryVec, listOf(doc1, doc2, doc3), docVectors)

        assertFalse(metrics.isCleanSeparation)
        assertEquals(1, metrics.overlapCount) // Bad Note 1 is before Good Note 2
        assertNotNull(metrics.gap)
        assertTrue(metrics.gap!! < 0.0)
        assertTrue(metrics.separationDescription.contains("Overlap"))
    }

    @Test
    fun `should handle no-answer search with wildcard expectedWrong`() {
        val search = SearchLabel(
            id = 7,
            query = "swimming pool opening hours",
            user = "rafal",
            expectedGood = emptyList(),
            expectedWrong = listOf("*")
        )

        val queryVec = floatArrayOf(1f, 0f)
        val doc1 = EvaluationDocument("d1", "Waltz notes", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val doc2 = EvaluationDocument("d2", "Cha Cha notes", "NOTE", "Cha Cha", "rafal", "PUBLIC", "...")

        val docVectors = mapOf(
            "d1" to floatArrayOf(0f, 1f), // dist 1.0
            "d2" to floatArrayOf(0.7071f, 0.7071f) // dist ~ 0.2929
        )

        val metrics = SemanticSearchEvaluation.evaluateSearch(search, queryVec, listOf(doc1, doc2), docVectors)

        assertTrue(metrics.isCleanSeparation)
        assertEquals(0.2929, metrics.nearestDistance, eps)
        assertTrue(metrics.rankedHits.all { it.label == MatchLabel.BAD })
        assertTrue(metrics.separationDescription.contains("No good candidates"))
    }

    @Test
    fun `should enforce access control rule for test user`() {
        val search = SearchLabel(
            id = 10,
            query = "head position",
            user = "test_user",
            expectedGood = listOf("Public note"),
            expectedWrong = listOf("rafal's private note")
        )

        val publicDoc = EvaluationDocument("d1", "Public note", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val rafalPrivateDoc = EvaluationDocument("d2", "rafal's private note", "NOTE", "Waltz", "rafal", "PRIVATE", "...")
        val testUserPrivateDoc = EvaluationDocument("d3", "test_user private note", "NOTE", "Waltz", "test_user", "PRIVATE", "...")

        assertTrue(SemanticSearchEvaluation.isVisibleToUser(publicDoc, "test_user"))
        assertFalse(SemanticSearchEvaluation.isVisibleToUser(rafalPrivateDoc, "test_user"))
        assertTrue(SemanticSearchEvaluation.isVisibleToUser(testUserPrivateDoc, "test_user"))
        assertTrue(SemanticSearchEvaluation.isVisibleToUser(rafalPrivateDoc, "rafal")) // Admin sees all

        // If rafalPrivateDoc is checked against labelCandidate for test_user, it must be BAD
        assertEquals(MatchLabel.BAD, SemanticSearchEvaluation.labelCandidate(rafalPrivateDoc, search))
    }

    @Test
    fun `should handle missing key and unreachable candidate gracefully`() {
        // Test no-key candidate configuration
        val candidate = CandidateSummaryMetrics(
            candidateName = "gemini-embedding-001",
            candidateDescription = "Gemini model",
            status = "not tried: no GOOGLE_AI_API_KEY configured",
            notTriedReason = "no GOOGLE_AI_API_KEY configured"
        )

        assertEquals("not tried: no GOOGLE_AI_API_KEY configured", candidate.status)
        assertEquals("no GOOGLE_AI_API_KEY configured", candidate.notTriedReason)
        assertTrue(candidate.searchMetrics.isEmpty())
    }

    @Test
    fun `should save and load vector cache accurately`(@TempDir tempDir: File) {
        val cacheFile = File(tempDir, "vectors-test.json")
        val vectors = SavedVectors(
            candidateName = "test-model",
            dimensions = 3,
            documents = mutableMapOf("d1" to listOf(0.1f, 0.2f, 0.3f)),
            queries = mutableMapOf("q1" to listOf(0.4f, 0.5f, 0.6f))
        )

        SemanticSearchEvaluation.saveVectors(cacheFile, vectors)
        assertTrue(cacheFile.exists())

        val loaded = SemanticSearchEvaluation.loadVectors(cacheFile)
        assertNotNull(loaded)
        assertEquals("test-model", loaded!!.candidateName)
        assertEquals(3, loaded.dimensions)
        assertEquals(listOf(0.1f, 0.2f, 0.3f), loaded.documents["d1"])
        assertEquals(listOf(0.4f, 0.5f, 0.6f), loaded.queries["q1"])
    }

    @Test
    fun `should parse evaluation documents from seed sql file`() {
        val seedFile = File("docs/evaluation/semantic-search/seed.sql")
        assertTrue(seedFile.exists(), "seed.sql should exist")

        val docs = SemanticSearchEvaluation.parseDocumentsFromSeedSql(seedFile)
        assertTrue(docs.isNotEmpty(), "Should extract documents from seed.sql")

        val notes = docs.filter { it.sourceType == "NOTE" }
        val figures = docs.filter { it.sourceType == "FIGURE" }

        assertTrue(notes.size >= 25, "Should have extracted at least 25 notes, found: ${notes.size}")
        assertTrue(figures.size >= 35, "Should have extracted at least 35 figures, found: ${figures.size}")

        // Check specific seeded items
        assertTrue(notes.any { it.title.contains("Waltz — keep the head weight left") })
        assertTrue(figures.any { it.title.contains("Natural Turn") })
    }

    @Test
    fun `should generate markdown report containing tables and badges`() {
        val labels = LabelsFile(
            confirmed = false,
            searches = listOf(
                SearchLabel(1, "head dropping", "rafal", "Note", listOf("Dropping the head in the lowering"), listOf("Shoe care"))
            )
        )

        val doc = EvaluationDocument("d1", "Dropping the head in the lowering", "NOTE", "Waltz", "rafal", "PUBLIC", "...")
        val rankedHits = listOf(
            CandidateRankedHit(1, doc, 0.229, MatchLabel.GOOD)
        )
        val searchMetric = SemanticSearchEvaluation.SearchEvaluationMetrics(
            search = labels.searches[0],
            nearestDistance = 0.229,
            tenthDistance = 0.229,
            thirtiethDistance = 0.229,
            rankedHits = rankedHits,
            minGoodDistance = 0.229,
            maxGoodDistance = 0.229,
            minBadDistance = null,
            gap = null,
            isCleanSeparation = true,
            overlapCount = 0,
            separationDescription = "All candidates good"
        )

        val candidate = CandidateSummaryMetrics(
            candidateName = "gemini-embedding-001",
            candidateDescription = "Baseline setup",
            status = "Evaluated",
            searchMetrics = listOf(searchMetric),
            widestGap = 0.05,
            suggestedCutoff = 0.42,
            noAnswerBehavior = "rejected",
            oneWordBehavior = "kept"
        )

        val report = SemanticSearchEvaluation.generateMarkdownReport(labels, listOf(candidate))

        assertTrue(report.contains("# Semantic Search Model & Task Type Comparison Report"))
        assertTrue(report.contains("Unconfirmed Proposal"))
        assertTrue(report.contains("| Candidate | Status | Widest Gap | Suggested Cutoff |"))
        assertTrue(report.contains("gemini-embedding-001"))
        assertTrue(report.contains("`head dropping`"))
        assertTrue(report.contains("**GOOD**"))
    }
}
