package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.service.*
import org.hamcrest.Matchers.containsString
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
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.UUID

@WebMvcTest(
    controllers = [CommentController::class],
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
@Import(CommentWebControllerTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class CommentWebControllerTest {

    @TestConfiguration
    class CsrfProcessorConfig : org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        override fun addArgumentResolvers(resolvers: MutableList<org.springframework.web.method.support.HandlerMethodArgumentResolver>) {
            resolvers.add(org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
        }
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var commentService: CommentService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var materialService: MaterialService

    // NavbarAdvice dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val materialId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val commentId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private val testUser = AppUser().apply {
        id = UUID.fromString("33333333-3333-3333-3333-333333333333")
        username = "dancer"
        displayName = "Marcus T."
    }

    private val testMaterial = Material().apply {
        id = materialId
        name = "Quickstep Routine"
    }

    private val sampleComment = Comment().apply {
        id = commentId
        material = testMaterial
        author = testUser
        content = "<div>Good <strong>timing</strong> on chasse</div>"
        createdAt = LocalDateTime.now()
    }

    @BeforeEach
    fun setUp() {
        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(appUserService.findByUsername("dancer")).thenReturn(testUser)
        `when`(materialService.findById(materialId)).thenReturn(testMaterial)
        `when`(commentService.getCommentsForMaterial(materialId)).thenReturn(listOf(sampleComment))
        `when`(commentService.findCommentById(commentId)).thenReturn(sampleComment)
    }

    @Test
    @WithMockUser(username = "dancer")
    fun `posting rich text comment re-renders comment list with trix editor`() {
        val richContent = "<div>Great <strong>frame</strong>!</div>"
        `when`(commentService.addComment(materialId, richContent, testUser)).thenReturn(
            Comment().apply {
                id = UUID.randomUUID()
                material = testMaterial
                author = testUser
                content = richContent
                createdAt = LocalDateTime.now()
            }
        )

        mockMvc.perform(
            post("/materials/$materialId/comments")
                .param("content", richContent)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("comment-section")))
            .andExpect(content().string(containsString("<trix-editor id=\"comment-content\"")))
            .andExpect(content().string(containsString("input=\"comment-content_input\"")))
            .andExpect(content().string(containsString("data-required=\"true\"")))
    }

    @Test
    @WithMockUser(username = "dancer")
    fun `posting structurally empty comment is refused with visible error message`() {
        val emptyContent = "<div><br></div>"
        `when`(commentService.addComment(materialId, emptyContent, testUser))
            .thenThrow(IllegalArgumentException("Comment content must not be blank"))

        mockMvc.perform(
            post("/materials/$materialId/comments")
                .param("content", emptyContent)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("comment-section")))
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("Comment content must not be blank")))
            .andExpect(content().string(containsString("<trix-editor id=\"comment-content\"")))
    }

    @Test
    @WithMockUser(username = "dancer")
    fun `posting blank comment is refused with visible error message`() {
        `when`(commentService.addComment(materialId, "", testUser))
            .thenThrow(IllegalArgumentException("Comment content must not be blank"))

        mockMvc.perform(
            post("/materials/$materialId/comments")
                .param("content", "")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("comment-section")))
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("Comment content must not be blank")))
    }

    @Test
    @WithMockUser(username = "dancer")
    fun `requesting edit form renders comment-edit-form with existing content in trix-editor`() {
        mockMvc.perform(
            get("/materials/$materialId/comments/$commentId/edit")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("<trix-editor id=\"comment-edit-$commentId\"")))
            .andExpect(content().string(containsString("input=\"comment-edit-${commentId}_input\"")))
            .andExpect(content().string(containsString("value=\"&lt;div&gt;Good &lt;strong&gt;timing&lt;/strong&gt; on chasse&lt;/div&gt;\"")))
    }

    @Test
    @WithMockUser(username = "dancer")
    fun `updating comment renders updated comment item with rich text`() {
        val updatedContent = "<div>Refined <em>posture</em> and timing</div>"
        val updatedComment = Comment().apply {
            id = commentId
            material = testMaterial
            author = testUser
            content = updatedContent
            createdAt = sampleComment.createdAt
            updatedAt = LocalDateTime.now()
        }
        `when`(commentService.findCommentById(commentId)).thenReturn(updatedComment)

        mockMvc.perform(
            put("/materials/$materialId/comments/$commentId")
                .param("content", updatedContent)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("rich-text")))
            .andExpect(content().string(containsString("Refined <em>posture</em> and timing")))
    }

    @Test
    @WithMockUser(username = "dancer")
    fun `updating comment with blank content is refused with visible error alert and preserves original note`() {
        `when`(commentService.updateComment(commentId, "", testUser))
            .thenThrow(IllegalArgumentException("Comment content must not be blank"))

        mockMvc.perform(
            put("/materials/$materialId/comments/$commentId")
                .param("content", "")
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("Comment content must not be blank")))
            .andExpect(content().string(containsString("<trix-editor id=\"comment-edit-$commentId\"")))
            .andExpect(content().string(containsString("value=\"&lt;div&gt;Good &lt;strong&gt;timing&lt;/strong&gt; on chasse&lt;/div&gt;\"")))
    }

    @Test
    @WithMockUser(username = "dancer")
    fun `updating comment with structurally empty content is refused with visible error alert and preserves original note`() {
        val emptyContent = "<div><br></div>"
        `when`(commentService.updateComment(commentId, emptyContent, testUser))
            .thenThrow(IllegalArgumentException("Comment content must not be blank"))

        mockMvc.perform(
            put("/materials/$materialId/comments/$commentId")
                .param("content", emptyContent)
                .with(csrf())
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("role=\"alert\"")))
            .andExpect(content().string(containsString("Comment content must not be blank")))
            .andExpect(content().string(containsString("<trix-editor id=\"comment-edit-$commentId\"")))
            .andExpect(content().string(containsString("value=\"&lt;div&gt;Good &lt;strong&gt;timing&lt;/strong&gt; on chasse&lt;/div&gt;\"")))
    }
}
