package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.Comment
import com.jankowski.rafal.dancebook.model.CommentAddedEvent
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.repository.CommentRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

class CommentServiceTest {

    private lateinit var commentRepository: CommentRepository
    private lateinit var materialService: MaterialService
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var richTextService: RichTextService
    private lateinit var commentService: CommentServiceImpl

    private val materialId = UUID.randomUUID()
    private val commentId = UUID.randomUUID()
    private val testUser = AppUser().apply {
        id = UUID.randomUUID()
        displayName = "Marcus T."
    }
    private val otherUser = AppUser().apply {
        id = UUID.randomUUID()
        displayName = "Other User"
    }
    private val testMaterial = Material().apply {
        id = materialId
        name = "Samba Basics"
    }

    @BeforeEach
    fun setUp() {
        commentRepository = mock(CommentRepository::class.java)
        materialService = mock(MaterialService::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        richTextService = RichTextServiceImpl()

        commentService = CommentServiceImpl(
            commentRepository = commentRepository,
            materialService = materialService,
            eventPublisher = eventPublisher,
            richTextService = richTextService
        )

        `when`(materialService.findById(materialId)).thenReturn(testMaterial)
    }

    @Test
    fun `addComment with valid content cleans and saves comment and publishes event`() {
        val rawContent = "<div>Great <strong>frame</strong>!</div>"
        `when`(commentRepository.save(any(Comment::class.java))).thenAnswer { invocation ->
            (invocation.arguments[0] as Comment).apply { id = commentId }
        }

        val result = commentService.addComment(materialId, rawContent, testUser)

        assertNotNull(result)
        assertEquals(rawContent, result.content)
        assertEquals(testUser, result.author)
        assertEquals(testMaterial, result.material)

        verify(commentRepository).save(any(Comment::class.java))
        verify(eventPublisher).publishEvent(any(CommentAddedEvent::class.java))
    }

    @Test
    fun `addComment with blank string throws IllegalArgumentException without saving`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            commentService.addComment(materialId, "", testUser)
        }
        assertEquals("Comment content must not be blank", ex.message)
        verify(commentRepository, never()).save(any(Comment::class.java))
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `addComment with whitespace only string throws IllegalArgumentException without saving`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            commentService.addComment(materialId, "   \t\n  ", testUser)
        }
        assertEquals("Comment content must not be blank", ex.message)
        verify(commentRepository, never()).save(any(Comment::class.java))
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `addComment with structurally empty rich text throws IllegalArgumentException without saving`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            commentService.addComment(materialId, "<div><br></div>", testUser)
        }
        assertEquals("Comment content must not be blank", ex.message)
        verify(commentRepository, never()).save(any(Comment::class.java))
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `addComment with empty formatting tags throws IllegalArgumentException without saving`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            commentService.addComment(materialId, "<div><strong> </strong><em></em></div>", testUser)
        }
        assertEquals("Comment content must not be blank", ex.message)
        verify(commentRepository, never()).save(any(Comment::class.java))
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `updateComment with valid content cleans and updates comment`() {
        val originalContent = "Original valid note"
        val existing = Comment().apply {
            id = commentId
            content = originalContent
            author = testUser
            material = testMaterial
            createdAt = LocalDateTime.now().minusDays(1)
        }
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(existing))
        `when`(commentRepository.save(any(Comment::class.java))).thenAnswer { invocation -> invocation.arguments[0] as Comment }

        val newContent = "<div>Updated <em>note</em></div>"
        val updated = commentService.updateComment(commentId, newContent, testUser)

        assertEquals(newContent, updated.content)
        assertNotNull(updated.updatedAt)
        verify(commentRepository).save(existing)
    }

    @Test
    fun `updateComment with blank string throws IllegalArgumentException and leaves original content intact`() {
        val originalContent = "Original note that must survive"
        val existing = Comment().apply {
            id = commentId
            content = originalContent
            author = testUser
            material = testMaterial
            createdAt = LocalDateTime.now().minusDays(1)
        }
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(existing))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            commentService.updateComment(commentId, "", testUser)
        }
        assertEquals("Comment content must not be blank", ex.message)
        assertEquals(originalContent, existing.content)
        assertNull(existing.updatedAt)
        verify(commentRepository, never()).save(any(Comment::class.java))
    }

    @Test
    fun `updateComment with structurally empty rich text throws IllegalArgumentException and leaves original content intact`() {
        val originalContent = "Original note that must survive"
        val existing = Comment().apply {
            id = commentId
            content = originalContent
            author = testUser
            material = testMaterial
            createdAt = LocalDateTime.now().minusDays(1)
        }
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(existing))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            commentService.updateComment(commentId, "<div><br></div>", testUser)
        }
        assertEquals("Comment content must not be blank", ex.message)
        assertEquals(originalContent, existing.content)
        assertNull(existing.updatedAt)
        verify(commentRepository, never()).save(any(Comment::class.java))
    }

    @Test
    fun `updateComment with empty formatting tags throws IllegalArgumentException and leaves original content intact`() {
        val originalContent = "Original note that must survive"
        val existing = Comment().apply {
            id = commentId
            content = originalContent
            author = testUser
            material = testMaterial
            createdAt = LocalDateTime.now().minusDays(1)
        }
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(existing))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            commentService.updateComment(commentId, "<div><strong> </strong></div>", testUser)
        }
        assertEquals("Comment content must not be blank", ex.message)
        assertEquals(originalContent, existing.content)
        assertNull(existing.updatedAt)
        verify(commentRepository, never()).save(any(Comment::class.java))
    }

    @Test
    fun `updateComment by different user throws AccessDeniedException`() {
        val existing = Comment().apply {
            id = commentId
            content = "Original note"
            author = testUser
            material = testMaterial
        }
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(existing))

        assertThrows(AccessDeniedException::class.java) {
            commentService.updateComment(commentId, "New text", otherUser)
        }
        verify(commentRepository, never()).save(any(Comment::class.java))
    }
}
