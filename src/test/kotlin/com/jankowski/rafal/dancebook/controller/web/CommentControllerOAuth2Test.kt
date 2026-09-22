package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.CalendarSyncService
import com.jankowski.rafal.dancebook.service.CommentService
import com.jankowski.rafal.dancebook.service.CustomListService
import com.jankowski.rafal.dancebook.service.MaterialService
import com.jankowski.rafal.dancebook.service.RichTextServiceImpl
import com.jankowski.rafal.dancebook.service.SystemSettingService
import org.junit.jupiter.api.AfterEach
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
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.test.context.TestSecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler
import org.springframework.security.web.servlet.support.csrf.CsrfRequestDataValueProcessor
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.UUID

/**
 * Comments are posted by users who signed in with Google, whose principal is a
 * [DefaultOAuth2User] and **not** a `UserDetails`. `CommentController` used to take the
 * current user via `@AuthenticationPrincipal userDetails: UserDetails`, which binds null
 * for that principal type — so every mutating comment endpoint threw a
 * `NullPointerException` on Kotlin's non-null check and the note never saved.
 *
 * These tests drive the endpoints with an OAuth2 principal, which is what the app actually
 * has in production. They fail against the old signature and pass against
 * `AppUserService.getCurrentUser()`, the pattern every other controller already uses because
 * it resolves both login types.
 */
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
@Import(CommentControllerOAuth2Test.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class CommentControllerOAuth2Test {

    @TestConfiguration
    class CsrfProcessorConfig : org.springframework.web.servlet.config.annotation.WebMvcConfigurer {

        /**
         * Registers the resolver Spring Security would normally contribute. Without it a
         * `@AuthenticationPrincipal UserDetails` parameter fails as an un-instantiable model
         * attribute instead of binding null, which would make this test go red for the wrong
         * reason rather than reproducing the NullPointerException seen in production.
         */
        override fun addArgumentResolvers(
            resolvers: MutableList<org.springframework.web.method.support.HandlerMethodArgumentResolver>
        ) {
            resolvers.add(
                org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()
            )
        }
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        /**
         * The comment fragment asks `#authorization.expression('hasRole(''ADMIN'')')` when deciding
         * whether to offer Delete. Thymeleaf's Spring Security dialect resolves that through a
         * SecurityExpressionHandler bean, which this slice would otherwise have none of.
         */
        @Bean
        fun webSecurityExpressionHandler() = DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var commentService: CommentService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var materialService: MaterialService

    // NavbarAdvice collaborators
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val materialId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val commentId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    /** Username and email deliberately differ, as they do for the real accounts. */
    private val testUser = AppUser().apply {
        id = UUID.fromString("33333333-3333-3333-3333-333333333333")
        username = "rafal"
        email = "dancer@example.com"
        displayName = "Rafal"
    }

    private val testMaterial = Material().apply {
        id = materialId
        name = "Quickstep Routine"
    }

    private val sampleComment = Comment().apply {
        id = commentId
        material = testMaterial
        author = testUser
        content = "Good timing on the chasse"
        createdAt = LocalDateTime.now()
    }

    /** What Google login actually puts in the security context: keyed on email, no UserDetails. */
    private fun googlePrincipal() = UsernamePasswordAuthenticationToken(
        DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf("email" to "dancer@example.com", "email_verified" to true),
            "email"
        ),
        null,
        listOf(SimpleGrantedAuthority("ROLE_USER"))
    )

    @BeforeEach
    fun setUp() {
        // addFilters = false means no Spring Security filter chain runs, and the
        // `authentication()` request post-processor relies on one to transfer the context. Setting
        // it here is what actually puts the OAuth2 principal where @AuthenticationPrincipal and
        // AppUserService.getCurrentUser() both look — without it this test would pass for the
        // wrong reason, having no principal at all rather than a non-UserDetails one.
        TestSecurityContextHolder.setContext(SecurityContextImpl(googlePrincipal()))

        `when`(appUserService.getCurrentUser()).thenReturn(testUser)
        `when`(materialService.findById(materialId)).thenReturn(testMaterial)
        `when`(commentService.getCommentsForMaterial(materialId)).thenReturn(listOf(sampleComment))
        `when`(commentService.findCommentById(commentId)).thenReturn(sampleComment)
    }

    @AfterEach
    fun tearDown() = TestSecurityContextHolder.clearContext()

    @Test
    fun `a user signed in with Google can post a comment`() {
        `when`(commentService.addComment(materialId, "Nice frame", testUser)).thenReturn(sampleComment)

        mockMvc.perform(
            post("/materials/$materialId/comments")
                .param("content", "Nice frame")
                .with(csrf())
        ).andExpect(status().isOk)
    }

    @Test
    fun `a user signed in with Google can edit their comment`() {
        mockMvc.perform(
            put("/materials/$materialId/comments/$commentId")
                .param("content", "Refined posture")
                .with(csrf())
        ).andExpect(status().isOk)
    }

    @Test
    fun `a user signed in with Google can delete their comment`() {
        mockMvc.perform(
            delete("/materials/$materialId/comments/$commentId")
                .with(csrf())
        ).andExpect(status().isOk)
    }
}
