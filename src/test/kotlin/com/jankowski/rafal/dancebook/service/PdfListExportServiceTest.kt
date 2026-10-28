package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.web.MaterialWebController
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Visibility
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/** The list PDF of #416: cover, filters, table of contents with page numbers, one section per item, both languages. */
@WebMvcTest(
    controllers = [MaterialWebController::class],
    excludeAutoConfiguration = [
        SecurityAutoConfiguration::class,
        OAuth2ClientAutoConfiguration::class,
        OAuth2ClientWebSecurityAutoConfiguration::class
    ],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [SecurityConfig::class])
    ]
)
@AutoConfigureMockMvc(addFilters = false)
@Import(
    RichTextServiceImpl::class,
    PdfExportServiceImpl::class,
    PdfListExportServiceTest.TestConfig::class
)
class PdfListExportServiceTest {

    @TestConfiguration
    class TestConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired
    private lateinit var pdfExportService: PdfExportService

    @MockitoBean private lateinit var commentService: CommentService
    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var trainingEventService: TrainingEventService

    // NavbarAdvice collaborators
    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private val owner = AppUser().apply {
        id = UUID.randomUUID()
        username = "anna"
        displayName = "Anna Tancerka"
    }

    private val waltz = DanceType().apply {
        id = UUID.randomUUID()
        name = "Waltz"
        category = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    }

    private fun note(index: Int, name: String = "Note number $index") = Material().apply {
        id = UUID.randomUUID()
        this.name = name
        description = "<div>Body of note $index</div>"
        this.owner = this@PdfListExportServiceTest.owner
        visibility = Visibility.PRIVATE
        danceType = waltz
        rating = 4
        createdAt = LocalDateTime.of(2026, 9, 1, 10, 0).plusMinutes(index.toLong())
    }

    private fun figure(index: Int, name: String = "Figure number $index") = DanceFigure().apply {
        id = UUID.randomUUID()
        this.name = name
        danceType = waltz
        danceClass = DanceClass.B
        notes = "<div>Technique of figure $index</div>"
        createdAt = LocalDateTime.of(2026, 8, 1, 9, 0)
    }

    /** Mockito's `any()` returns null, which a non-null Kotlin UUID parameter rejects; register the matcher and return a placeholder. */
    private fun anyUuid(): UUID {
        any<UUID>()
        return UUID(0, 0)
    }

    private fun stubNoteCollaborators() {
        `when`(materialService.findFiguresByMaterial(anyUuid())).thenReturn(emptyList())
        `when`(commentService.getCommentsForMaterial(anyUuid())).thenAnswer { invocation ->
            listOf(Comment().apply {
                id = UUID.randomUUID()
                author = owner
                content = "<div>Comment on ${invocation.getArgument<UUID>(0)}</div>"
                createdAt = LocalDateTime.of(2026, 9, 2, 12, 0)
            })
        }
    }

    private fun text(bytes: ByteArray): Pair<Int, String> =
        Loader.loadPDF(bytes).use { it.numberOfPages to PDFTextStripper().getText(it) }

    @Test
    fun `note list pdf has a cover with filters, a table of contents and one section per note in English`() {
        stubNoteCollaborators()
        val notes = listOf(note(1, "Zażółć gęślą jaźń"), note(2), note(3))
        val cover = PdfListCover(
            ownerName = "Anna Tancerka",
            filters = listOf(PdfFilterLine("pdf.list.filter.style", "Waltz"), PdfFilterLine("pdf.list.filter.search", "turn")),
            totalItems = 3
        )

        val bytes = pdfExportService.exportMaterialListPdf(notes, cover, Locale.ENGLISH)

        val (pages, text) = text(bytes)
        assertTrue(pages >= 5, "cover + contents + three notes, got $pages pages")
        assertTrue(text.contains("DANCEBOOK EXPORT"), "the cover brand is upper-cased by the stylesheet")
        assertTrue(text.contains("Anna Tancerka"))
        assertTrue(text.contains("Filters used"))
        assertTrue(text.contains("Style") && text.contains("Waltz"))
        assertTrue(text.contains("Search") && text.contains("turn"))
        assertTrue(text.contains("Contents"), "table of contents heading")
        listOf("Zażółć gęślą jaźń", "Note number 2", "Note number 3").forEach { title ->
            assertTrue(Regex(Regex.escape(title)).findAll(text).count() >= 2, "$title is in the contents and in its own section")
        }
        assertTrue(text.contains("Body of note 3"))
        assertTrue(text.contains("Comment on ${notes[0].id}"), "all comments are included")
        assertTrue(!text.contains("Part 1 of"), "a single part does not say 'Part 1 of 1'")
        // Table of contents lines end in a page number, e.g. "1 Zażółć gęślą jaźń · Waltz .... 3"
        assertTrue(Regex("Note number 2 · Waltz\\s*\\.{3,}\\s*4").containsMatchIn(text), "TOC page number for the second note:\n$text")
    }

    @Test
    fun `note list pdf is in Polish and labels the part`() {
        stubNoteCollaborators()
        val cover = PdfListCover(
            ownerName = "Anna Tancerka",
            filters = emptyList(),
            part = 2,
            totalParts = 3,
            totalItems = 250
        )

        val bytes = pdfExportService.exportMaterialListPdf(listOf(note(101)), cover, Locale.forLanguageTag("pl"))

        val (_, text) = text(bytes)
        assertTrue(text.contains("Część 2 z 3"), text)
        assertTrue(text.contains("Spis treści"))
        assertTrue(text.contains("Użyte filtry"))
        assertTrue(text.contains("Brak, uwzględniono wszystkie pozycje."))
        assertTrue(text.contains("1 w tym pliku, 250 łącznie"), text)
        assertTrue(text.contains("Właściciel"))
    }

    @Test
    fun `figure list pdf has a cover, a table of contents and one section per figure`() {
        val figures = listOf(figure(1), figure(2, "Spin Turn"))
        val cover = PdfListCover(
            ownerName = "Anna Tancerka",
            filters = listOf(PdfFilterLine("pdf.list.filter.class", "Class B")),
            part = 1,
            totalParts = 2,
            totalItems = 140
        )

        val bytes = pdfExportService.exportDanceFigureListPdf(figures, cover, Locale.ENGLISH)

        val (pages, text) = text(bytes)
        assertTrue(pages >= 4, "cover + contents + two figures, got $pages pages")
        assertTrue(text.contains("Part 1 of 2"))
        assertTrue(text.contains("Contents"))
        assertTrue(text.contains("Class B"))
        assertTrue(text.contains("Spin Turn"))
        assertTrue(text.contains("Technique of figure 1"))
        assertTrue(Regex("Spin Turn · Waltz\\s*\\.{3,}\\s*4").containsMatchIn(text), "TOC page number:\n$text")
    }

    @Test
    fun `a list export refuses more than the maximum number of items`() {
        stubNoteCollaborators()
        val tooMany = (1..PdfExportService.MAX_ITEMS_PER_PDF + 1).map { note(it) }
        assertThrows(IllegalArgumentException::class.java) {
            pdfExportService.exportMaterialListPdf(tooMany, PdfListCover("Anna", emptyList()), Locale.ENGLISH)
        }
    }

    @Test
    fun `part count rounds up and is at least one`() {
        assertEquals(1, PdfExportService.partCount(0))
        assertEquals(1, PdfExportService.partCount(1))
        assertEquals(1, PdfExportService.partCount(100))
        assertEquals(2, PdfExportService.partCount(101))
        assertEquals(3, PdfExportService.partCount(250))
    }

    /**
     * Measurement for the PR (#416): a full 100-note part, each with a comment. Prints wall time, PDF size, pages and
     * the heap retained after the export. The bounds are loose on purpose; the numbers are what matters.
     */
    @Test
    fun `a full 100-note part renders and is measured`() {
        stubNoteCollaborators()
        val notes = (1..100).map { note(it, "Measured note $it") }
        val cover = PdfListCover("Anna Tancerka", listOf(PdfFilterLine("pdf.list.filter.style", "Waltz")), 1, 3, 250)

        // warm up the engine and fonts so the measurement is of a steady export, not of class loading
        pdfExportService.exportMaterialListPdf(notes.take(2), cover, Locale.ENGLISH)

        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        var peak = before
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
                try { Thread.sleep(5) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; start() }

        val started = System.nanoTime()
        val bytes = pdfExportService.exportMaterialListPdf(notes, cover, Locale.ENGLISH)
        val millis = (System.nanoTime() - started) / 1_000_000
        sampler.interrupt()

        val pages = Loader.loadPDF(bytes).use { it.numberOfPages }
        println(
            "PDF-EXPORT-MEASUREMENT 100 notes: ${millis} ms, ${bytes.size / 1024} KiB, $pages pages, " +
                "heap before ${before / (1024 * 1024)} MiB, sampled peak ${peak / (1024 * 1024)} MiB " +
                "(+${(peak - before) / (1024 * 1024)} MiB), max heap ${runtime.maxMemory() / (1024 * 1024)} MiB"
        )
        assertTrue(pages >= 102, "cover + contents + 100 notes, got $pages")
        assertTrue(millis < 60_000, "100-note export took $millis ms")
    }
}
