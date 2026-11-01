package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.FigureSuggestionService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.NoteRewriteService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import com.jankowski.rafal.dancebook.service.TrainingEventService
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.core.userdetails.User
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.util.UUID

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
@Import(MaterialFormMediaSectionTest.TestConfig::class, RichTextServiceImpl::class)
class MaterialFormMediaSectionTest {

    @TestConfiguration
    class TestConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var materialService: MaterialService
    @MockitoBean private lateinit var danceTypeService: DanceTypeService
    @MockitoBean private lateinit var danceCategoryService: DanceCategoryService
    @MockitoBean private lateinit var commentService: CommentService
    @MockitoBean private lateinit var danceFigureService: DanceFigureService
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var noteRewriteService: NoteRewriteService
    @MockitoBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockitoBean private lateinit var trainingEventService: TrainingEventService

    @MockitoBean private lateinit var customListService: CustomListService
    @MockitoBean private lateinit var activityEventService: ActivityEventService
    @MockitoBean private lateinit var systemSettingService: SystemSettingService
    @MockitoBean private lateinit var calendarSyncService: CalendarSyncService
    @MockitoBean private lateinit var activeCalendarService: ActiveCalendarService

    private lateinit var testUser: AppUser
    private val materialId: UUID = UUID.randomUUID()
    private val danceCategory = DanceCategory().apply {
        id = UUID.randomUUID()
        name = "Standard"
    }
    private val danceType = DanceType().apply {
        id = UUID.randomUUID()
        name = "Slow Foxtrot"
        category = danceCategory
    }

    @BeforeEach
    fun setUp() {
        testUser = AppUser().apply {
            id = UUID.randomUUID()
            username = "test_dancer"
            displayName = "Test Dancer"
            email = "dancer@example.com"
            role = Role.USER
            locale = "en"
        }
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.getCurrentUserOrNull()).thenReturn(testUser)

        val principal = User("test_dancer", "password", listOf(SimpleGrantedAuthority("ROLE_USER")))
        val auth = UsernamePasswordAuthenticationToken(principal, "password", principal.authorities)
        TestSecurityContextHolder.setContext(SecurityContextImpl(auth))

        `when`(danceCategoryService.findAll()).thenReturn(listOf(danceCategory))
        `when`(danceTypeService.findAll()).thenReturn(listOf(danceType))
    }

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    @Test
    fun `new note form opens with media section collapsed and default hint`() {
        val result = mockMvc.perform(get("/materials/new").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details, "Media section details element must exist")
        assertNull(details!!.attr("open").takeIf { it.isNotEmpty() }, "Media section must be collapsed by default on new note")

        val summary = details.selectFirst("summary")
        assertNotNull(summary)
        assertTrue(summary!!.text().contains("Video and source"))

        val hint = details.selectFirst("#mediaHint")
        assertNotNull(hint)
        assertEquals("Video link, source link, Google Drive upload", hint!!.text().trim())

        // Check that all controls exist inside media section
        assertNotNull(details.selectFirst("input[name=videoLink]"))
        assertNotNull(details.selectFirst("input[name=sourceLink]"))
        assertNotNull(details.selectFirst("input[name=driveFileId]"))
        assertNotNull(details.selectFirst("#videoFile"))
        assertNotNull(details.selectFirst("#manualDriveId"))
        assertNotNull(details.selectFirst("#uploadBtn"))
    }

    @Test
    fun `edit note form with videoLink opens media section and shows video hint`() {
        val material = Material().apply {
            id = materialId
            name = "Note with Video"
            videoLink = "https://youtube.com/watch?v=dQw4w9WgXcQ"
            owner = testUser
            danceType = this@MaterialFormMediaSectionTest.danceType
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}/edit", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)
        assertTrue(details!!.hasAttr("open"), "Media section must be open when video link exists")

        val hint = details.selectFirst("#mediaHint")
        assertNotNull(hint)
        assertEquals("Video link attached", hint!!.text().trim())
    }

    @Test
    fun `edit note form with sourceLink opens media section and shows source hint`() {
        val material = Material().apply {
            id = materialId
            name = "Note with Source"
            sourceLink = "https://facebook.com/dance/post/1"
            owner = testUser
            danceType = this@MaterialFormMediaSectionTest.danceType
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}/edit", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)
        assertTrue(details!!.hasAttr("open"), "Media section must be open when source link exists")

        val hint = details.selectFirst("#mediaHint")
        assertNotNull(hint)
        assertEquals("Source link attached", hint!!.text().trim())
    }

    @Test
    fun `edit note form with driveFileId opens media section and shows drive hint`() {
        val material = Material().apply {
            id = materialId
            name = "Note with Drive"
            driveFileId = "1A2B3C4D5E"
            owner = testUser
            danceType = this@MaterialFormMediaSectionTest.danceType
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}/edit", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)
        assertTrue(details!!.hasAttr("open"), "Media section must be open when Drive file exists")

        val hint = details.selectFirst("#mediaHint")
        assertNotNull(hint)
        assertEquals("Drive video attached", hint!!.text().trim())
    }

    @Test
    fun `edit note form with multiple media opens media section and shows multiple hint`() {
        val material = Material().apply {
            id = materialId
            name = "Note with Multiple Media"
            videoLink = "https://youtube.com/watch?v=123"
            sourceLink = "https://facebook.com/123"
            owner = testUser
            danceType = this@MaterialFormMediaSectionTest.danceType
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}/edit", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)
        assertTrue(details!!.hasAttr("open"), "Media section must be open when multiple media exist")

        val hint = details.selectFirst("#mediaHint")
        assertNotNull(hint)
        assertEquals("Multiple media attached", hint!!.text().trim())
    }

    @Test
    fun `edit note form with no media remains collapsed`() {
        val material = Material().apply {
            id = materialId
            name = "Note without Media"
            owner = testUser
            danceType = this@MaterialFormMediaSectionTest.danceType
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}/edit", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)
        assertNull(details!!.attr("open").takeIf { it.isNotEmpty() }, "Media section must be collapsed when note has no media")

        val hint = details.selectFirst("#mediaHint")
        assertNotNull(hint)
        assertEquals("Video link, source link, Google Drive upload", hint!!.text().trim())
    }

    @Test
    fun `validation error in videoLink opens media section`() {
        val result = mockMvc.perform(
            post("/materials")
                .with(csrf())
                .param("name", "Valid Note Name")
                .param("version", "0")
                .param("videoLink", "invalid-not-a-url")
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)
        assertTrue(details!!.hasAttr("open"), "Media section must be open when video link has validation error")
    }

    @Test
    fun `validation error in name only does not open media section on new note`() {
        val result = mockMvc.perform(
            post("/materials")
                .with(csrf())
                .param("name", "")
                .param("version", "0")
        )
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)
        assertNull(details!!.attr("open").takeIf { it.isNotEmpty() }, "Media section must remain collapsed when error is outside media section")
    }

    @Test
    fun `media section renders in Polish when user locale is pl`() {
        testUser.locale = "pl"

        val result = mockMvc.perform(get("/materials/new").with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val doc = Jsoup.parse(result.response.contentAsString)
        val details = doc.selectFirst("#mediaSection")
        assertNotNull(details)

        val summary = details!!.selectFirst("summary")
        assertNotNull(summary)
        assertTrue(summary!!.text().contains("Wideo i źródło"))

        val hint = details.selectFirst("#mediaHint")
        assertNotNull(hint)
        assertEquals("Link do wideo, link do źródła, plik z Dysku Google", hint!!.text().trim())
    }
}
