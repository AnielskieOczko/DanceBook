package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.Visibility
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.DanceCategoryService
import com.jankowski.rafal.dancebook.service.DanceFigureService
import com.jankowski.rafal.dancebook.service.DanceTypeService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.NoteRewriteService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
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
@Import(MaterialViewRenderingTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class MaterialViewRenderingTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() =
            org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var materialService: MaterialService
    @MockBean private lateinit var danceTypeService: DanceTypeService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService
    @MockBean private lateinit var commentService: CommentService
    @MockBean private lateinit var danceFigureService: DanceFigureService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var noteRewriteService: NoteRewriteService

    // NavbarAdvice dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val materialId: UUID = UUID.randomUUID()
    private val ownerUser = AppUser().apply {
        id = UUID.randomUUID()
        username = "owner"
        displayName = "Rafal"
        role = Role.USER
    }

    private val danceCategory = DanceCategory().apply {
        id = UUID.randomUUID()
        name = "Latin"
    }

    private val danceType = DanceType().apply {
        id = UUID.randomUUID()
        name = "Samba"
        category = danceCategory
    }

    @BeforeEach
    fun setUp() {
        val auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken(ownerUser, null, emptyList())
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication = auth
        `when`(appUserService.getCurrentUser()).thenReturn(ownerUser)
        `when`(materialService.findFiguresByMaterial(materialId)).thenReturn(emptyList())
        `when`(commentService.getCommentsForMaterial(materialId)).thenReturn(emptyList())
    }

    @Test
    fun `note with no drive file, video link, or source link has no media section in markup at all`() {
        val material = Material().apply {
            id = materialId
            name = "Samba walks without media"
            description = "<div>Basic walks description</div>"
            owner = ownerUser
            this.danceType = this@MaterialViewRenderingTest.danceType
            driveFileId = null
            videoLink = null
            sourceLink = null
            rating = 4
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        mockMvc.perform(get("/materials/{id}", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Video and source"))))
            .andExpect(content().string(not(containsString("<video"))))
            .andExpect(content().string(not(containsString("A video to watch for this note"))))
            .andExpect(content().string(not(containsString("Where this material came from"))))
    }

    @Test
    fun `note with blank media links also omits media section in markup`() {
        val material = Material().apply {
            id = materialId
            name = "Note with blank links"
            owner = ownerUser
            driveFileId = "   "
            videoLink = ""
            sourceLink = "   "
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        mockMvc.perform(get("/materials/{id}", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(not(containsString("Video and source"))))
            .andExpect(content().string(not(containsString("<video"))))
    }

    @Test
    fun `note with driveFileId renders 16-9 video player under Video and source`() {
        val material = Material().apply {
            id = materialId
            name = "Samba walks with Drive video"
            owner = ownerUser
            this.danceType = this@MaterialViewRenderingTest.danceType
            driveFileId = "drive-file-abc-123"
            videoLink = null
            sourceLink = null
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        assertTrue(html.contains("Video and source"))
        val videoElement = doc.selectFirst("video[src='/materials/$materialId/video']")
        assertNotNull(videoElement, "Must render video player with stream endpoint")
        assertNull(doc.selectFirst("a:contains(A video to watch)"), "Must not render video link text")
        assertNull(doc.selectFirst("a:contains(Where this material came from)"), "Must not render source link text")
    }

    @Test
    fun `note with videoLink only renders external video link with description under Video and source`() {
        val material = Material().apply {
            id = materialId
            name = "Samba walks with YouTube video"
            owner = ownerUser
            this.danceType = this@MaterialViewRenderingTest.danceType
            driveFileId = null
            videoLink = "https://youtube.com/watch?v=samba123"
            sourceLink = null
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        assertTrue(html.contains("Video and source"))
        assertNull(doc.selectFirst("video"), "Must not render video player")
        val link = doc.selectFirst("a[href='https://youtube.com/watch?v=samba123']")
        assertNotNull(link, "Must render external video link")
        assertTrue(html.contains("A video to watch for this note"))
        assertFalse(html.contains("Where this material came from"))
    }

    @Test
    fun `note with sourceLink only renders source link labelled as source`() {
        val material = Material().apply {
            id = materialId
            name = "Samba walks with Instagram source"
            owner = ownerUser
            this.danceType = this@MaterialViewRenderingTest.danceType
            driveFileId = null
            videoLink = null
            sourceLink = "https://instagram.com/p/dance456"
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        assertTrue(html.contains("Video and source"))
        assertNull(doc.selectFirst("video"), "Must not render video player")
        val source = doc.selectFirst("a[href='https://instagram.com/p/dance456']")
        assertNotNull(source, "Must render source link")
        assertTrue(html.contains("Where this material came from"))
        assertFalse(html.contains("A video to watch for this note"))
    }

    @Test
    fun `note with driveFileId, videoLink, and sourceLink renders all media components`() {
        val material = Material().apply {
            id = materialId
            name = "Samba walks with all media"
            owner = ownerUser
            driveFileId = "drive-xyz-789"
            videoLink = "https://vimeo.com/999"
            sourceLink = "https://facebook.com/dance/posts/111"
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        assertTrue(html.contains("Video and source"))
        assertNotNull(doc.selectFirst("video"), "Must render video player")
        assertNotNull(doc.selectFirst("a[href='https://vimeo.com/999']"), "Must render external video link")
        assertNotNull(doc.selectFirst("a[href='https://facebook.com/dance/posts/111']"), "Must render source link")
        assertTrue(html.contains("A video to watch for this note"))
        assertTrue(html.contains("Where this material came from"))
    }

    @Test
    fun `note view renders serif title, readable measure, back link, edit button, and updated wording`() {
        val material = Material().apply {
            id = materialId
            name = "Samba walks: keeping the bounce"
            description = "<div>From Tuesday's Latin class. Keep the bounce.</div>"
            owner = ownerUser
            this.danceType = this@MaterialViewRenderingTest.danceType
            rating = 4
            visibility = Visibility.PUBLIC
            createdAt = LocalDateTime.of(2026, 9, 16, 10, 0)
            updatedAt = LocalDateTime.of(2026, 9, 22, 12, 0)
        }
        `when`(materialService.findById(materialId)).thenReturn(material)

        val result = mockMvc.perform(get("/materials/{id}", materialId).with(csrf()))
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val doc = Jsoup.parse(html)

        // Title in Source Serif 4
        val title = doc.selectFirst("article h1")
        assertNotNull(title)
        assertEquals("Samba walks: keeping the bounce", title!!.text().trim())
        assertTrue(title.classNames().contains("font-serif"))

        // Note text in long-form prose style
        val prose = doc.selectFirst(".rich-text")
        assertNotNull(prose)
        assertTrue(prose!!.classNames().contains("prose-text"))

        // Rating renders as 4/5 without spaces
        val ratingElem = doc.selectFirst("article .flex.items-center.gap-1")
        assertNotNull(ratingElem)
        assertTrue(ratingElem!!.text().contains("4/5"))
        assertFalse(ratingElem.text().contains("4 /5"))

        // Public badge uses badge-success
        val badge = doc.selectFirst(".badge-success")
        assertNotNull(badge, "Public badge must render badge-success")
        assertEquals("Public", badge!!.text().trim())

        // Contextual header: Notes back link & Edit note button
        val backLink = doc.selectFirst("header a[href='/materials']")
        assertNotNull(backLink)
        assertTrue(backLink!!.text().contains("Notes"))

        val editButton = doc.selectFirst("header a[href='/materials/$materialId/edit']")
        assertNotNull(editButton)
        assertTrue(editButton!!.text().contains("Edit note"))

        // Figures heading
        val figuresHeading = doc.selectFirst("#pinnedFiguresSection h2")
        assertNotNull(figuresHeading)
        assertTrue(figuresHeading!!.text().contains("Figures"))

        // Comments heading
        val commentsHeading = doc.selectFirst("#comment-section h2")
        assertNotNull(commentsHeading)
        assertTrue(commentsHeading!!.text().contains("Comments"))

        // Wording checks: neither sequence nor Director appears anywhere in the rendered HTML
        assertFalse(html.contains("sequence", ignoreCase = true), "HTML must not contain 'sequence'")
        assertFalse(html.contains("Director", ignoreCase = true), "HTML must not contain 'Director'")
    }
}
