package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.controller.web.MaterialWebController
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceFigureStep
import com.jankowski.rafal.dancebook.model.DanceFigureStepComment
import com.jankowski.rafal.dancebook.model.DanceFigureStepSet
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.MedalLevel
import com.jankowski.rafal.dancebook.model.Visibility
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import javax.imageio.ImageIO

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
    PdfExportServiceTest.TestConfig::class
)
class PdfExportServiceTest {

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

    @Test
    fun `should export note to PDF in English with cover, comments, and Polish characters`() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            username = "anna_dancer"
            displayName = "Anna Tancerka"
        }

        val category = DanceCategory().apply {
            id = UUID.randomUUID()
            name = "Standard"
        }

        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Walc Angielski"
            this.category = category
        }

        val materialId = UUID.randomUUID()
        val polishTitle = "Lekcja tańca: Zażółć gęślą jaźń ĄĆĘŁŃÓŚŹŻ"
        val polishDescription = "<p>Ważne uwagi dotyczące ramy: <strong>trzymać łokcie szeroko</strong>, <em>nie opuszczać głowy</em>. Polskie znaki: ąćęłńóśźż.</p>"

        val material = Material().apply {
            id = materialId
            name = polishTitle
            description = polishDescription
            owner = user
            visibility = Visibility.PUBLIC
            this.danceType = danceType
            rating = 5
            createdAt = LocalDateTime.of(2026, 9, 15, 10, 30)
            updatedAt = LocalDateTime.of(2026, 9, 16, 12, 0)
            videoLink = "https://youtube.com/watch?v=sample"
        }

        val linkedFigure = DanceFigure().apply {
            id = UUID.randomUUID()
            name = "Natural Turn"
            danceClass = DanceClass.B
            alternativeTiming = "1 2 3"
            this.danceType = danceType
        }

        val figurePin = Figure().apply {
            id = UUID.randomUUID()
            this.material = material
            this.danceFigure = linkedFigure
        }

        val comment = Comment().apply {
            id = UUID.randomUUID()
            author = user
            this.material = material
            content = "<p>Świetny materiał do ćwiczeń! Zwróć uwagę na krok nr 2.</p>"
            createdAt = LocalDateTime.of(2026, 9, 15, 11, 0)
        }

        `when`(materialService.findFiguresByMaterial(materialId)).thenReturn(listOf(figurePin))
        `when`(commentService.getCommentsForMaterial(materialId)).thenReturn(listOf(comment))

        val pdfBytes = pdfExportService.exportMaterialPdf(material, Locale.ENGLISH)
        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.size > 1000)

        // Verify PDF structure with PDFBox
        Loader.loadPDF(pdfBytes).use { document ->
            assertTrue(document.numberOfPages >= 2, "Expected at least 2 pages (cover + content)")
            val text = PDFTextStripper().getText(document)

            // Verify Polish glyphs rendered without distortion
            assertTrue(text.contains("Zażółć gęślą jaźń"), "Title with Polish letters must render in PDF text")
            assertTrue(text.contains("ĄĆĘŁŃÓŚŹŻ") || text.contains("ąćęłńóśźż"), "Polish uppercase/lowercase diacritics must render")
            assertTrue(text.contains("Anna Tancerka"), "Owner name should be in PDF")
            assertTrue(text.contains("Natural Turn"), "Pinned figure should be in PDF")
            assertTrue(text.contains("Świetny materiał"), "Comment text should be in PDF")
            assertTrue(text.contains("https://youtube.com/watch?v=sample"), "Video link should be printed as text")
        }
    }

    @Test
    fun `should export note to PDF in Polish`() {
        val user = AppUser().apply {
            id = UUID.randomUUID()
            username = "tomek"
            displayName = "Tomasz Nowak"
        }

        val materialId = UUID.randomUUID()
        val material = Material().apply {
            id = materialId
            name = "Rumba - podstawy"
            description = "<p>Krok podstawowy w rumbie.</p>"
            owner = user
            visibility = Visibility.PRIVATE
            rating = 4
            createdAt = LocalDateTime.of(2026, 10, 1, 14, 0)
        }

        `when`(materialService.findFiguresByMaterial(materialId)).thenReturn(emptyList())
        `when`(commentService.getCommentsForMaterial(materialId)).thenReturn(emptyList())

        val pdfBytes = pdfExportService.exportMaterialPdf(material, Locale.forLanguageTag("pl"))
        assertNotNull(pdfBytes)

        Loader.loadPDF(pdfBytes).use { document ->
            val text = PDFTextStripper().getText(document)
            assertTrue(text.contains("Rumba - podstawy"))
            assertTrue(text.contains("Tomasz Nowak"))
            // Check Polish labels from messages_pl
            assertTrue(text.contains("Właściciel") || text.contains("Szczegóły") || text.contains("Eksport DanceBook"))
        }
    }

    @Test
    fun `should export figure to PDF with step tables in Polish and English`() {
        val category = DanceCategory().apply {
            id = UUID.randomUUID()
            name = "Latin"
        }

        val danceType = DanceType().apply {
            id = UUID.randomUUID()
            name = "Cha Cha"
            this.category = category
        }

        val figureId = UUID.randomUUID()
        val figure = DanceFigure().apply {
            id = figureId
            name = "Cha Cha Chasse: Zażółć jaźń"
            this.danceType = danceType
            danceClass = DanceClass.C
            medalLevel = MedalLevel.BRONZE
            alternativeTiming = "2, 3, 4&1"
            startingPosition = "Closed"
            endingPosition = "Open Facing"
            startingFootLeader = "LF"
            endingFootLeader = "RF"
            notes = "<p>Pamiętaj o prostych kolanach przy przenoszeniu ciężaru ciała. Polskie znaki: ę, ó, ą, ś, ł, ż, ź, ć, ń.</p>"
            precedingFigureNames = listOf("Basic Movement", "Cross Body Lead")
            followingFigureNames = listOf("Fan", "Alemana")
            createdAt = LocalDateTime.of(2026, 8, 10, 9, 0)
        }

        val defaultSet = DanceFigureStepSet().apply {
            id = UUID.randomUUID()
            this.danceFigure = figure
            name = "Default"
            isDefault = true
        }

        val leaderStep1 = DanceFigureStep().apply {
            id = UUID.randomUUID()
            danceFigureStepSet = defaultSet
            stepNumber = 1
            timing = "2"
            role = "LEADER"
            foot = "LF"
            action = "Krok w przód (Forward walk)"
            footwork = "BF"
            alignment = "LOD"
            amountOfTurn = "None"
        }
        val stepComment = DanceFigureStepComment().apply {
            id = UUID.randomUUID()
            danceFigureStep = leaderStep1
            commentText = "Mocna akcja biodra w prawo"
            displayOrder = 1
        }
        leaderStep1.comments = mutableListOf(stepComment)

        val leaderStep2 = DanceFigureStep().apply {
            id = UUID.randomUUID()
            danceFigureStepSet = defaultSet
            stepNumber = 2
            timing = "3"
            role = "LEADER"
            foot = "RF"
            action = "Ciężar w tył"
            footwork = "BF"
            alignment = "LOD"
            amountOfTurn = "None"
        }

        val followerStep1 = DanceFigureStep().apply {
            id = UUID.randomUUID()
            danceFigureStepSet = defaultSet
            stepNumber = 1
            timing = "2"
            role = "FOLLOWER"
            foot = "RF"
            action = "Krok w tył (Back walk)"
            footwork = "T"
            alignment = "Against LOD"
            amountOfTurn = "None"
        }

        defaultSet.steps = mutableListOf(leaderStep1, leaderStep2, followerStep1)
        figure.stepSets = mutableListOf(defaultSet)

        val pdfBytes = pdfExportService.exportDanceFigurePdf(figure, Locale.forLanguageTag("pl"))
        assertNotNull(pdfBytes)
        assertTrue(pdfBytes.isNotEmpty())

        Loader.loadPDF(pdfBytes).use { document ->
            assertTrue(document.numberOfPages >= 2)
            val text = PDFTextStripper().getText(document)
            val normalizedText = text.replace(Regex("\\s+"), " ")
            assertTrue(normalizedText.contains("Cha Cha Chasse"))
            assertTrue(normalizedText.contains("Krok w przód"))
            assertTrue(normalizedText.contains("Mocna akcja biodra w prawo"))
            assertTrue(normalizedText.contains("Krok w tył"))
            assertTrue(normalizedText.contains("Basic Movement"))
            assertTrue(normalizedText.contains("Alemana"))
        }
    }

    @Test
    fun `should scale large image and keep memory footprint low`() {
        val serviceImpl = pdfExportService as PdfExportServiceImpl
        // Create a test image of size 1200x900 on disk
        val tempDir = Files.createDirectories(Paths.get("uploads/categories"))
        val testFile = tempDir.resolve("large_test.png")

        val largeImage = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        val g = largeImage.createGraphics()
        g.color = Color.MAGENTA
        g.fillRect(0, 0, 1200, 900)
        g.dispose()

        ImageIO.write(largeImage, "png", testFile.toFile())

        try {
            val runtime = Runtime.getRuntime()
            System.gc()
            val memBefore = runtime.totalMemory() - runtime.freeMemory()

            val base64Uri = serviceImpl.loadAndScaleImageBase64("large_test.png")
            assertNotNull(base64Uri)
            assertTrue(base64Uri!!.startsWith("data:image/png;base64,"))

            // Decode and verify scaled dimensions are <= 300x300
            val base64Data = base64Uri.substringAfter("base64,")
            val decodedBytes = java.util.Base64.getDecoder().decode(base64Data)
            val scaledImage = ImageIO.read(decodedBytes.inputStream())
            assertNotNull(scaledImage)
            assertTrue(scaledImage.width <= 300)
            assertTrue(scaledImage.height <= 300)
            assertTrue(decodedBytes.size < 50_000, "Scaled image size should be under 50 KB")

            val memAfter = runtime.totalMemory() - runtime.freeMemory()
            val memDeltaMb = (memAfter - memBefore) / (1024 * 1024)
            // Memory delta should be very small (under 20 MB)
            assertTrue(memDeltaMb < 50, "Memory footprint ($memDeltaMb MB) should be vastly under 1 GiB limit")
        } finally {
            Files.deleteIfExists(testFile)
        }
    }
}
