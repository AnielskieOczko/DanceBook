# AI assistant: confirmed drafts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The assistant can draft a note, a training session or a figure from a chat message. Nothing is written until the user presses **Save** on the draft card, and **Edit in form** opens the normal create form prefilled with the draft.

**Architecture:** Three `draft_*` tools validate a request DTO and store it as an `assistant_draft` row (status `PENDING`); they never call a domain service. The card's **Save** runs a claim → execute → finish-or-release sequence: a short transaction locks the row and moves it to `SAVED`, the same service methods the forms call run outside it (Google Calendar I/O must not sit inside a DB transaction here), and on failure the row is released back to `PENDING` with the error. Ids the model names are dropped unless an earlier tool result in the same conversation (or the page the user is on) contained them.

**Tech Stack:** Kotlin 1.9, Spring Boot 3.5, Spring AI tool calling (`@Tool`), Spring Data JPA + Flyway (Postgres `jsonb`), Thymeleaf + HTMX 2, JUnit 5 + Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-25-ai-assistant-design.md` (the "Edit in form" paragraph was added for this issue). **Issue:** #149. **Builds on:** #148 (merged) and #142 (merged).

## Global Constraints

- **Schema changes need a Flyway migration.** `ddl-auto=validate`. The highest existing version is **V37**, so this is **V38**.
- **Request DTOs give every bound field a default**, and `MaterialRequest.version` stays required (no default).
- **Never put the word `new` (lowercase, followed by a space) inside a `${...}` in a fragment expression.** `ThymeleafRestrictedExpressionTest` scans for it. Build such strings in a `th:with`.
- **A conditional and a fragment inclusion must not share an element.** Put `th:if` on an enclosing `<th:block>` (`ThymeleafConditionalIncludeTest`).
- **Fragments take named parameters; an omitted parameter is `null`.** Every new catalog fragment goes into `FragmentCatalogRenderingTest`'s lists and `catalog-harness.html`; the test fails on a literal `null` or an unparsed `th:` in the output.
- **Every icon goes through `fragments/icon :: icon`.** Extra classes go through `cls`, never `class`.
- **Tailwind scans templates, `static/js` and `src/main/kotlin` for literal class names.** Never build a class name by concatenation.
- **Use design tokens only** (`primary`, `on-primary`, `surface`, `surface-container`, `outline`, `outline-variant`, `on-surface`, `on-surface-variant`, `error`, `warning`); no raw palette values. Form-control borders use `outline`, not `outline-variant`.
- **Services, never repositories**, are how the assistant reaches domain data, so access rules apply automatically (spec: "Tools go through the services").
- **A mutating service call publishes its domain event** (`AFTER_COMMIT` listeners). Save must therefore call the existing service methods; it never writes domain tables itself.
- **Hidden is 404.** Another user's draft id is `EntityNotFoundException` (mapped to 404), never 403.
- **htmx does not swap 4xx responses.** A card action that must show the user something answers 200 with the card fragment.
- **No form may post the same field name twice** (`FormFieldDuplication`). Carry a list as one comma-separated hidden input.
- **MockMvc tests that POST need `.with(csrf())`.** Tests in `@WebMvcTest(addFilters = false)` slices set a signed-in user with `TestSecurityContextHolder.setContext(...)`, not the `authentication()` post-processor.
- **Mockito's `any()` returns null, which a non-null Kotlin parameter refuses.** Stub and verify with real values, or hand-write a fake. There is no `mockito-kotlin`.
- **Write Bash commands with literal paths and no shell variables or `cd` prefixes** (the session already starts in the repo).
- **No Claude attribution** in commit messages or the PR body.

## Review Focus

The spec is silent on these inputs; each has a test in the named task.

1. A figure id the model sends that is not a UUID at all (`"natural-turn"`): dropped like any invented id, the rest of the draft survives, the model is told what was left out. (Task 4)
2. The model calls `draft_note` in the same round as the search that would have grounded its ids: the ids are not stored yet, so they are dropped and the result tells the model to search first. (Task 4)
3. The user double-clicks **Save**, or has the conversation open in two tabs: exactly one note is created; the second Save gets the card showing the saved state. (Tasks 2 and 5)
4. A session draft whose end time is not after its start, or an `eventType` that is not one of the enum values: no draft, a readable problem for the model, and after the second failure an instruction to ask the user. (Task 4)
5. A note text containing `<script>` or other markup: stored escaped as paragraphs, and sanitised again by `MaterialService.create`; the card shows plain text. (Tasks 1 and 5)

---

### Task 0: Branch

**Files:** none

- [ ] **Step 1: Create the feature branch from an up-to-date main**

```bash
git checkout main
git pull --ff-only
git checkout -b feat/ai-assistant-drafts-149
```

Expected: `Switched to a new branch 'feat/ai-assistant-drafts-149'`.

---

### Task 1: Draft schema, entity, codec and request fields

**Files:**
- Create: `src/main/resources/db/migration/V38__add_assistant_draft.sql`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/model/DraftKind.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/model/DraftStatus.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/model/AssistantDraft.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/repository/AssistantDraftRepository.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDraftDtos.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftCodec.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantText.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/dto/MaterialDto.kt` (add two fields to `MaterialRequest`)
- Modify: `docs/superpowers/specs/2026-09-25-ai-assistant-design.md` (record the table differences)
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/DraftTestSupport.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantTextTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftCodecTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftRepositoryIntegrationTest.kt`

**Interfaces:**
- Produces:
  - `enum class DraftKind { NOTE, TRAINING_EVENT, FIGURE }`, `enum class DraftStatus { PENDING, SAVED, DISCARDED }`
  - `class AssistantDraft` with `id: UUID?`, `conversation: AssistantConversation?`, `message: AssistantMessage?`, `kind: DraftKind`, `payload: MutableMap<String, Any?>`, `status: DraftStatus`, `savedEntityId: UUID?`, `notice: String?`, `createdAt: LocalDateTime`
  - `AssistantDraftRepository.findOwned(id: UUID, ownerId: UUID): AssistantDraft?` and `findOwnedForUpdate(id, ownerId)` (pessimistic write lock)
  - `DraftField(label, value)`, `DraftFigureLine(name, timing, url)`, `DraftView(id, kind, status, heading, fields, figures, savedUrl, notice)`
  - `AssistantDraftCodec.toPayload(request: Any): MutableMap<String, Any?>` and `read(payload: Map<String, Any?>, type: Class<T>): T`
  - `AssistantText.toRichText(plain: String?): String?`
  - `MaterialRequest.figureIds: List<UUID>` and `MaterialRequest.markAttended: Boolean`
  - Test helper `DraftTestSupport.mapper()` and `DraftTestSupport.validator()`

- [ ] **Step 1: Write the failing unit tests**

`src/test/kotlin/com/jankowski/rafal/dancebook/service/DraftTestSupport.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jankowski.rafal.dancebook.dto.RichTextLengthValidator
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorFactory
import jakarta.validation.Validation
import jakarta.validation.Validator

/** Shared wiring for the draft tests: the mapper the app builds, and a real Bean Validation validator. */
object DraftTestSupport {

    /** Kotlin and java.time support, dates as ISO strings, as Spring Boot configures it. */
    fun mapper(): ObjectMapper =
        jacksonObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /** `@RichTextLength` asks Spring for its `RichTextService`; outside Spring we hand it one. */
    fun validator(): Validator {
        val factory = Validation.byDefaultProvider().configure()
            .constraintValidatorFactory(object : ConstraintValidatorFactory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ConstraintValidator<*, *>> getInstance(key: Class<T>): T =
                    if (key == RichTextLengthValidator::class.java) {
                        RichTextLengthValidator(RichTextServiceImpl()) as T
                    } else {
                        key.getDeclaredConstructor().newInstance()
                    }

                override fun releaseInstance(instance: ConstraintValidator<*, *>) {}
            })
            .buildValidatorFactory()
        return factory.validator
    }
}
```

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantTextTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AssistantTextTest {

    @Test
    fun `each non-blank line becomes a div paragraph`() {
        assertEquals("<div>Feather step</div><div>Head drops</div>", AssistantText.toRichText("Feather step\n\n  Head drops  \n"))
    }

    @Test
    fun `markup in the text is escaped, never passed through`() {
        assertEquals(
            "<div>&lt;script&gt;alert(1)&lt;/script&gt; &amp; more</div>",
            AssistantText.toRichText("<script>alert(1)</script> & more")
        )
    }

    @Test
    fun `blank or missing text is null, so the field stays empty`() {
        assertNull(AssistantText.toRichText(null))
        assertNull(AssistantText.toRichText("  \n \r\n"))
    }
}
```

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftCodecTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftCodecTest {

    private val codec = AssistantDraftCodec(DraftTestSupport.mapper())

    @Test
    fun `a note request survives the round trip with its pins and attendance flag`() {
        val figure = UUID.randomUUID()
        val session = UUID.randomUUID()
        val request = MaterialRequest(
            name = "Tuesday class", description = "<div>Head drops</div>", version = 0,
            trainingEventId = session, figureIds = listOf(figure), markAttended = true
        )

        val back = codec.read(codec.toPayload(request), MaterialRequest::class.java)

        assertEquals(request, back)
    }

    @Test
    fun `a session request survives the round trip, dates and all`() {
        val request = TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(12, 0), segments = mutableListOf(TrainingEventSegmentRequest(UUID.randomUUID(), 60))
        )

        val payload = codec.toPayload(request)
        assertEquals("2026-10-03", payload["date"], "dates are stored as ISO strings, readable in the jsonb column")
        assertEquals(request, codec.read(payload, TrainingEventRequest::class.java))
    }

    @Test
    fun `a figure request survives the round trip`() {
        val request = DanceFigureRequest(name = "Heel Turn", danceTypeId = UUID.randomUUID(), danceClass = DanceClass.D, alternativeTiming = "1 2 3")

        assertEquals(request, codec.read(codec.toPayload(request), DanceFigureRequest::class.java))
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantTextTest" --tests "com.jankowski.rafal.dancebook.service.AssistantDraftCodecTest"`
Expected: compile FAIL (`AssistantText`, `AssistantDraftCodec` and `MaterialRequest.figureIds` are not defined).

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V38__add_assistant_draft.sql`:

```sql
-- Migration V38: what the assistant drafted for the user to confirm (#149).
-- A draft belongs to a conversation, so it is private to that conversation's owner and goes
-- when the conversation does. message_id is the tool message that produced it; it is set just
-- after that message is stored, so it starts null. notice is the error of a failed Save while
-- the draft is PENDING, or the warning of a Save that succeeded with a caveat.

CREATE TABLE assistant_draft (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID         NOT NULL REFERENCES assistant_conversation(id) ON DELETE CASCADE,
    message_id       UUID         REFERENCES assistant_message(id) ON DELETE CASCADE,
    kind             VARCHAR(20)  NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    saved_entity_id  UUID,
    notice           TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_assistant_draft_conversation ON assistant_draft(conversation_id);
```

- [ ] **Step 4: Write the model, repository and DTOs**

`src/main/kotlin/com/jankowski/rafal/dancebook/model/DraftKind.kt`:

```kotlin
package com.jankowski.rafal.dancebook.model

/** What a draft would create. Picks the payload's request type and the service Save calls. */
enum class DraftKind { NOTE, TRAINING_EVENT, FIGURE }
```

`src/main/kotlin/com/jankowski/rafal/dancebook/model/DraftStatus.kt`:

```kotlin
package com.jankowski.rafal.dancebook.model

/**
 * `PENDING` can be saved. `SAVED` was (or is being) saved: a draft is saved at most once.
 * `DISCARDED` was opened in the form instead, so the card is greyed out and cannot be saved.
 */
enum class DraftStatus { PENDING, SAVED, DISCARDED }
```

`src/main/kotlin/com/jankowski/rafal/dancebook/model/AssistantDraft.kt`:

```kotlin
package com.jankowski.rafal.dancebook.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "assistant_draft")
class AssistantDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    var conversation: AssistantConversation? = null

    /** The tool message that produced this draft. Null until that message has been stored. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id")
    var message: AssistantMessage? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var kind: DraftKind = DraftKind.NOTE

    /** The validated request DTO for [kind], as JSON. A MutableMap so Hibernate sees `Map<String, Object>`. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var payload: MutableMap<String, Any?> = mutableMapOf()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: DraftStatus = DraftStatus.PENDING

    @Column(name = "saved_entity_id")
    var savedEntityId: UUID? = null

    @Column(columnDefinition = "text")
    var notice: String? = null

    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
}
```

`src/main/kotlin/com/jankowski/rafal/dancebook/repository/AssistantDraftRepository.kt`:

```kotlin
package com.jankowski.rafal.dancebook.repository

import com.jankowski.rafal.dancebook.model.AssistantDraft
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AssistantDraftRepository : JpaRepository<AssistantDraft, UUID> {

    /** The draft, only if its conversation belongs to [ownerId]. */
    @Query("select d from AssistantDraft d where d.id = :id and d.conversation.owner.id = :ownerId")
    fun findOwned(@Param("id") id: UUID, @Param("ownerId") ownerId: UUID): AssistantDraft?

    /** As [findOwned], holding a row lock until the transaction ends, so two Saves cannot both win. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AssistantDraft d where d.id = :id and d.conversation.owner.id = :ownerId")
    fun findOwnedForUpdate(@Param("id") id: UUID, @Param("ownerId") ownerId: UUID): AssistantDraft?
}
```

`src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDraftDtos.kt`:

```kotlin
package com.jankowski.rafal.dancebook.dto

import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import java.util.UUID

/** One labelled line of a draft card, for example "Session" / "Standard group class". */
data class DraftField(val label: String, val value: String)

/** A figure on a note draft: its name, its timing ("S Q Q") when it has one, and its page. */
data class DraftFigureLine(val name: String, val timing: String?, val url: String)

/**
 * Everything a draft card shows. [savedUrl] is set once the draft is `SAVED`. [notice] is the
 * error of a failed Save (draft still `PENDING`) or a caveat of a Save that went through.
 */
data class DraftView(
    val id: UUID,
    val kind: DraftKind,
    val status: DraftStatus,
    val heading: String,
    val fields: List<DraftField>,
    val figures: List<DraftFigureLine>,
    val savedUrl: String?,
    val notice: String?
)
```

- [ ] **Step 5: Write the codec and text helper, and extend `MaterialRequest`**

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftCodec.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import org.springframework.stereotype.Component

/**
 * Turns a request DTO into the `payload` map of a draft and back. It reads with
 * `FAIL_ON_UNKNOWN_PROPERTIES` off because Jackson also writes the DTOs' computed properties
 * (`isRepeating`, `effectiveStepSets`), which have no constructor parameter to read back into.
 */
@Component
@ConditionalOnAssistant
class AssistantDraftCodec(objectMapper: ObjectMapper) {

    private val mapper: ObjectMapper = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun toPayload(request: Any): MutableMap<String, Any?> =
        mapper.convertValue(request, object : TypeReference<MutableMap<String, Any?>>() {})

    fun <T> read(payload: Map<String, Any?>, type: Class<T>): T = mapper.convertValue(payload, type)
}
```

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantText.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

/** Plain text from the model, made into the markup the rich-text fields store. */
object AssistantText {

    /**
     * One `<div>` per non-blank line, which is what the Trix editor emits and what the
     * rich-text safelist allows. Markup in the text is escaped, so nothing the model writes can
     * become markup. Null when there is nothing to show.
     */
    fun toRichText(plain: String?): String? {
        val html = plain.orEmpty()
            .replace("\r\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<div>${escape(it)}</div>" }
        return html.ifEmpty { null }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
```

In `src/main/kotlin/com/jankowski/rafal/dancebook/dto/MaterialDto.kt`, replace the last field of `MaterialRequest`:

```kotlin
    val trainingEventId: UUID? = null
)

data class MaterialResponse(
```

with:

```kotlin
    val trainingEventId: UUID? = null,

    /**
     * Create only. Catalog figures to pin once the note exists. Set when the note comes from an
     * assistant draft (#149): the create form has no figure picker, so it shows them as chips and
     * carries them as one comma-separated hidden field. Not stored on the note itself.
     */
    val figureIds: List<UUID> = emptyList(),

    /** Create only. Record [trainingEventId]'s session as attended, for a note written from a draft. */
    val markAttended: Boolean = false
)

data class MaterialResponse(
```

- [ ] **Step 6: Run the unit tests**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantTextTest" --tests "com.jankowski.rafal.dancebook.service.AssistantDraftCodecTest"`
Expected: PASS (6 tests).

- [ ] **Step 7: Write the repository integration test** (needs Docker)

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftRepositoryIntegrationTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar"])
class AssistantDraftRepositoryIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser

    private fun newUser(name: String) = appUsers.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        drafts.deleteAll()
        messages.deleteAll()
        conversations.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
    }

    private fun aliceDraft(): AssistantDraft {
        val conversation = conversations.save(com.jankowski.rafal.dancebook.model.AssistantConversation().apply {
            owner = alice; title = "wrap up"
        })
        return drafts.save(AssistantDraft().apply {
            this.conversation = conversation
            kind = DraftKind.NOTE
            payload = mutableMapOf("name" to "Tuesday class", "figureIds" to listOf("f1"))
        })
    }

    @Test
    fun `a draft keeps its jsonb payload and starts pending`() {
        val saved = aliceDraft()

        val loaded = drafts.findOwned(saved.id!!, alice.id!!)!!

        assertEquals(DraftStatus.PENDING, loaded.status)
        assertEquals("Tuesday class", loaded.payload["name"])
        assertEquals(listOf("f1"), loaded.payload["figureIds"])
        assertNull(loaded.savedEntityId)
        assertNull(loaded.message)
    }

    @Test
    fun `only the conversation's owner can find or lock the draft`() {
        val saved = aliceDraft()

        assertNotNull(drafts.findOwned(saved.id!!, alice.id!!))
        assertNull(drafts.findOwned(saved.id!!, bob.id!!))
        assertNull(drafts.findOwnedForUpdate(saved.id!!, bob.id!!))
    }

    @Test
    fun `deleting the conversation deletes its drafts`() {
        val saved = aliceDraft()

        conversations.deleteById(saved.conversation!!.id!!)

        assertFalse(drafts.findById(saved.id!!).isPresent)
    }
}
```

- [ ] **Step 8: Run it**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftRepositoryIntegrationTest"`
Expected: PASS (3 tests). If Flyway or Hibernate validation fails, the migration and entity disagree; fix the column names.

- [ ] **Step 9: Record the table differences in the spec**

In `docs/superpowers/specs/2026-09-25-ai-assistant-design.md`, replace the `assistant_draft` bullet list:

```
- `assistant_draft`:
  - `id`, `message_id` (FK)
  - `kind`: `NOTE`, `TRAINING_EVENT` or `FIGURE`
  - `payload` (jsonb: the validated request DTO)
  - `status`: `PENDING`, `SAVED` or `DISCARDED`
  - `saved_entity_id` (nullable)
  - `created_at`

  **A draft can be saved at most once.** Saving checks and moves the status in the same
  transaction.
```

with:

```
- `assistant_draft`:
  - `id`, `conversation_id` (FK, cascade on delete: ownership runs through the conversation)
  - `message_id` (FK, nullable: the tool message that produced it, set just after that message
    is stored)
  - `kind`: `NOTE`, `TRAINING_EVENT` or `FIGURE`
  - `payload` (jsonb: the validated request DTO)
  - `status`: `PENDING`, `SAVED` or `DISCARDED`
  - `saved_entity_id` (nullable)
  - `notice` (nullable: the error of a failed Save, or the caveat of a Save that went through)
  - `created_at`

  **A draft can be saved at most once.** Saving claims the draft in its own short transaction
  (a row lock, then `PENDING` → `SAVED`), runs the service calls that the form runs outside
  that transaction, because the training service writes to Google Calendar and this codebase
  keeps that I/O out of database transactions, and then records the created id. If the service
  calls fail, the draft is released back to `PENDING` with the error in `notice`.
```

- [ ] **Step 10: Commit**

```bash
git add src/main/resources/db/migration/V38__add_assistant_draft.sql src/main/kotlin/com/jankowski/rafal/dancebook/model/DraftKind.kt src/main/kotlin/com/jankowski/rafal/dancebook/model/DraftStatus.kt src/main/kotlin/com/jankowski/rafal/dancebook/model/AssistantDraft.kt src/main/kotlin/com/jankowski/rafal/dancebook/repository/AssistantDraftRepository.kt src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDraftDtos.kt src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftCodec.kt src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantText.kt src/main/kotlin/com/jankowski/rafal/dancebook/dto/MaterialDto.kt docs/superpowers/specs/2026-09-25-ai-assistant-design.md src/test/kotlin/com/jankowski/rafal/dancebook/service/DraftTestSupport.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantTextTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftCodecTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftRepositoryIntegrationTest.kt
git commit -m "feat: add the assistant_draft table, its codec and the note request's pin fields (#149)"
```

---

### Task 2: The draft store: claim once, release on failure

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/DraftNotPendingException.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftStore.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftStoreIntegrationTest.kt`

**Interfaces:**
- Consumes: `AssistantDraftRepository`, `AssistantMessageRepository`, `AssistantConversationService.findOwned(id)`, `AppUserService.getCurrentUser()`.
- Produces (`AssistantDraftStore`, every method `@Transactional`; it returns entities but callers must read only scalar fields, never `conversation` or `message`, which are lazy):
  - `create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): AssistantDraft`
  - `attach(draftId: UUID, messageId: UUID)`
  - `findOwned(id: UUID): AssistantDraft` (throws `EntityNotFoundException`)
  - `findOpenOrNull(id: UUID, kind: DraftKind): AssistantDraft?` (not `SAVED`, right kind, owned; else null)
  - `claim(id: UUID): AssistantDraft` (`PENDING` → `SAVED`; throws `DraftNotPendingException` otherwise)
  - `finish(id: UUID, entityId: UUID, notice: String?)`
  - `release(id: UUID, notice: String)`
  - `discardForForm(id: UUID): AssistantDraft` (`PENDING`/`DISCARDED` → `DISCARDED`; `SAVED` throws `DraftNotPendingException`)
  - `class DraftNotPendingException(val status: DraftStatus) : RuntimeException`

- [ ] **Step 1: Write the failing integration test** (needs Docker)

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftStoreIntegrationTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class AssistantDraftStoreIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var store: AssistantDraftStore
    @Autowired private lateinit var conversationService: AssistantConversationService
    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser

    private fun newUser(name: String) = appUsers.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        drafts.deleteAll()
        messages.deleteAll()
        conversations.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
        `when`(appUserService.getCurrentUser()).thenReturn(alice)
    }

    private fun actAs(user: AppUser) {
        `when`(appUserService.getCurrentUser()).thenReturn(user)
    }

    private fun aliceDraft(): UUID {
        val conversation = conversationService.start("wrap up")
        return store.create(conversation.id!!, DraftKind.FIGURE, mutableMapOf("name" to "Heel Turn")).id!!
    }

    @Test
    fun `claiming moves a pending draft to saved, and a second claim is refused`() {
        val id = aliceDraft()

        assertEquals(DraftStatus.SAVED, store.claim(id).status)
        val refused = assertThrows(DraftNotPendingException::class.java) { store.claim(id) }
        assertEquals(DraftStatus.SAVED, refused.status)
    }

    @Test
    fun `two saves at the same moment: exactly one claims the draft`() {
        val id = aliceDraft()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Boolean> {
                    start.await()
                    try { store.claim(id); true } catch (e: DraftNotPendingException) { false }
                }
            }
            start.countDown()
            val won = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, won.count { it }, "one claim wins, the other is refused: $won")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `finish records what was created, and release puts a failed save back to pending with the error`() {
        val id = aliceDraft()
        store.claim(id)
        store.release(id, "Name is too short")

        val released = store.findOwned(id)
        assertEquals(DraftStatus.PENDING, released.status)
        assertEquals("Name is too short", released.notice)

        store.claim(id)
        val entity = UUID.randomUUID()
        store.finish(id, entity, "Saved, but not linked")
        val finished = store.findOwned(id)
        assertEquals(DraftStatus.SAVED, finished.status)
        assertEquals(entity, finished.savedEntityId)
        assertEquals("Saved, but not linked", finished.notice)
    }

    @Test
    fun `release never reopens a draft that was finished`() {
        val id = aliceDraft()
        store.claim(id)
        store.finish(id, UUID.randomUUID(), null)

        store.release(id, "late failure")

        assertEquals(DraftStatus.SAVED, store.findOwned(id).status)
    }

    @Test
    fun `opening in the form discards a pending draft, which can then no longer be saved`() {
        val id = aliceDraft()

        assertEquals(DraftStatus.DISCARDED, store.discardForForm(id).status)
        assertEquals(DraftStatus.DISCARDED, store.discardForForm(id).status, "opening twice is fine")
        assertThrows(DraftNotPendingException::class.java) { store.claim(id) }
    }

    @Test
    fun `a saved draft cannot be opened in the form, and is not offered to the form`() {
        val id = aliceDraft()
        store.claim(id)

        assertThrows(DraftNotPendingException::class.java) { store.discardForForm(id) }
        assertNull(store.findOpenOrNull(id, DraftKind.FIGURE))
    }

    @Test
    fun `a pending or discarded draft is offered to the form of its own kind only`() {
        val id = aliceDraft()

        assertNotNull(store.findOpenOrNull(id, DraftKind.FIGURE))
        assertNull(store.findOpenOrNull(id, DraftKind.NOTE))
        store.discardForForm(id)
        assertNotNull(store.findOpenOrNull(id, DraftKind.FIGURE))
    }

    @Test
    fun `user B cannot find, claim, discard or attach to user A's draft`() {
        val id = aliceDraft()
        val message = conversationService.append(
            drafts.findById(id).get().conversation!!.id!!, AssistantRole.TOOL, "draft_figure"
        )
        actAs(bob)

        assertThrows(EntityNotFoundException::class.java) { store.findOwned(id) }
        assertThrows(EntityNotFoundException::class.java) { store.claim(id) }
        assertThrows(EntityNotFoundException::class.java) { store.discardForForm(id) }
        assertThrows(EntityNotFoundException::class.java) { store.attach(id, message.id!!) }
        assertNull(store.findOpenOrNull(id, DraftKind.FIGURE))
        assertEquals(DraftStatus.PENDING, drafts.findById(id).get().status)
    }

    @Test
    fun `a draft cannot be created in someone else's conversation`() {
        val conversation = conversationService.start("mine")
        actAs(bob)

        assertThrows(EntityNotFoundException::class.java) {
            store.create(conversation.id!!, DraftKind.NOTE, mutableMapOf("name" to "x"))
        }
    }

    @Test
    fun `attach links the draft to its tool message`() {
        val id = aliceDraft()
        val conversationId = drafts.findById(id).get().conversation!!.id!!
        val message = conversationService.append(conversationId, AssistantRole.TOOL, "draft_figure")

        store.attach(id, message.id!!)

        assertEquals(message.id, drafts.findById(id).get().message?.id)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftStoreIntegrationTest"`
Expected: compile FAIL (`AssistantDraftStore`, `DraftNotPendingException` do not exist).

- [ ] **Step 3: Implement the exception and the store**

`src/main/kotlin/com/jankowski/rafal/dancebook/service/DraftNotPendingException.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.model.DraftStatus

/** Raised when a draft that is already saved or discarded is saved again, or a saved one opened in the form. */
class DraftNotPendingException(val status: DraftStatus) :
    RuntimeException("This draft is already ${status.name.lowercase()}")
```

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftStore.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The drafts table and nothing else: it moves a draft through its states and never touches a
 * domain service. Every read is scoped to the current user through the draft's conversation, so
 * someone else's draft is the same [EntityNotFoundException] as a missing one.
 *
 * Methods return the entity for its scalar fields (`kind`, `status`, `payload`, `savedEntityId`,
 * `notice`). The `conversation` and `message` associations are lazy and closed by then.
 */
@Service
@ConditionalOnAssistant
class AssistantDraftStore(
    private val drafts: AssistantDraftRepository,
    private val messages: AssistantMessageRepository,
    private val conversations: AssistantConversationService,
    private val appUserService: AppUserService
) {

    @Transactional
    fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): AssistantDraft {
        val conversation = conversations.findOwned(conversationId)
        return drafts.save(AssistantDraft().apply {
            this.conversation = conversation
            this.kind = kind
            this.payload = payload
        })
    }

    /** Ties the draft to the tool message that produced it. A message from another conversation is ignored. */
    @Transactional
    fun attach(draftId: UUID, messageId: UUID) {
        val draft = owned(draftId)
        val message = messages.findById(messageId).orElse(null)
        if (message != null && message.conversation?.id == draft.conversation?.id) {
            draft.message = message
            drafts.save(draft)
        }
    }

    @Transactional(readOnly = true)
    fun findOwned(id: UUID): AssistantDraft = owned(id)

    /** The draft for a create form to prefill from: owned, of [kind], and not already saved. Otherwise null. */
    @Transactional(readOnly = true)
    fun findOpenOrNull(id: UUID, kind: DraftKind): AssistantDraft? =
        drafts.findOwned(id, currentUserId())?.takeIf { it.kind == kind && it.status != DraftStatus.SAVED }

    /** Step one of Save: under a row lock, `PENDING` becomes `SAVED` and commits, so a second Save is refused. */
    @Transactional
    fun claim(id: UUID): AssistantDraft {
        val draft = lockedOwned(id)
        if (draft.status != DraftStatus.PENDING) throw DraftNotPendingException(draft.status)
        draft.status = DraftStatus.SAVED
        draft.notice = null
        return drafts.save(draft)
    }

    /** The last step of a good Save: what was created, and a caveat to show if there is one. */
    @Transactional
    fun finish(id: UUID, entityId: UUID, notice: String?) {
        val draft = owned(id)
        draft.savedEntityId = entityId
        draft.notice = notice
        drafts.save(draft)
    }

    /** A Save that failed: back to `PENDING` with the error. A draft that finished in the meantime stays saved. */
    @Transactional
    fun release(id: UUID, notice: String) {
        val draft = lockedOwned(id)
        if (draft.status == DraftStatus.SAVED && draft.savedEntityId == null) {
            draft.status = DraftStatus.PENDING
            draft.notice = notice
            drafts.save(draft)
        }
    }

    /** "Edit in form": the draft is greyed out from here on, so it cannot also be saved from the card. */
    @Transactional
    fun discardForForm(id: UUID): AssistantDraft {
        val draft = lockedOwned(id)
        if (draft.status == DraftStatus.SAVED) throw DraftNotPendingException(draft.status)
        draft.status = DraftStatus.DISCARDED
        return drafts.save(draft)
    }

    private fun currentUserId(): UUID = appUserService.getCurrentUser().id!!

    private fun owned(id: UUID): AssistantDraft =
        drafts.findOwned(id, currentUserId()) ?: throw EntityNotFoundException("Draft not found")

    private fun lockedOwned(id: UUID): AssistantDraft =
        drafts.findOwnedForUpdate(id, currentUserId()) ?: throw EntityNotFoundException("Draft not found")
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftStoreIntegrationTest"`
Expected: PASS (9 tests). The concurrency test failing with `2` winners means the row lock is not applied: check `@Lock` on `findOwnedForUpdate` and that `claim` is `@Transactional`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/DraftNotPendingException.kt src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftStore.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftStoreIntegrationTest.kt
git commit -m "feat: add the draft store with a locked single-use claim (#149)"
```

---

### Task 3: Turn scope and grounding

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantTurnScope.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantGrounding.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantGroundingTest.kt`

**Interfaces:**
- Consumes: `AssistantConversationService.messages(conversationId)`, `ToolResult`/`ResultCard` (`kind`, `id`), `ResolvedPage` and `PageContextType`.
- Produces:
  - `class ToolTurn(val conversationId: UUID, val page: ResolvedPage)` with `val failures: MutableMap<String, Int>`
  - `class AssistantTurnScope` with `fun <T> run(turn: ToolTurn, block: () -> T): T` and `fun current(): ToolTurn?`
  - `data class Grounded(val figures: Set<UUID>, val notes: Set<UUID>, val sessions: Set<UUID>)`
  - `class AssistantGrounding(conversations, objectMapper)` with `fun idsFor(turn: ToolTurn): Grounded`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantGroundingTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.dto.ResolvedPage
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AssistantGroundingTest {

    private val user = AppUser().apply { id = UUID.randomUUID(); displayName = "Rafał" }
    private lateinit var conversations: FakeAssistantConversationService
    private lateinit var conversationId: UUID
    private lateinit var grounding: AssistantGrounding

    @BeforeEach
    fun setUp() {
        conversations = FakeAssistantConversationService(user)
        conversationId = conversations.start("wrap up").id!!
        grounding = AssistantGrounding(conversations, DraftTestSupport.mapper())
    }

    private fun toolResult(vararg cards: Pair<String, UUID>) {
        conversations.append(
            conversationId, AssistantRole.TOOL, "search",
            mutableMapOf(
                "name" to "search",
                "result" to mutableMapOf(
                    "total" to cards.size,
                    "items" to cards.map { (kind, id) ->
                        mutableMapOf<String, Any?>("kind" to kind, "id" to id.toString(), "title" to "t", "url" to "/x", "chips" to emptyList<String>())
                    }
                )
            )
        )
    }

    private fun turn(page: ResolvedPage = ResolvedPage(PageContextType.HOME, null, null)) = ToolTurn(conversationId, page)

    @Test
    fun `ids come only from tool results, sorted by the kind of card that carried them`() {
        val figure = UUID.randomUUID(); val note = UUID.randomUUID(); val session = UUID.randomUUID()
        toolResult("figure" to figure, "note" to note)
        toolResult("session" to session)
        conversations.append(conversationId, AssistantRole.USER, "an id in user text ${UUID.randomUUID()}")

        val grounded = grounding.idsFor(turn())

        assertEquals(setOf(figure), grounded.figures)
        assertEquals(setOf(note), grounded.notes)
        assertEquals(setOf(session), grounded.sessions)
    }

    @Test
    fun `a figure id is not grounded as a session id`() {
        val figure = UUID.randomUUID()
        toolResult("figure" to figure)

        assertTrue(figure !in grounding.idsFor(turn()).sessions)
    }

    @Test
    fun `the page the user is on is grounded as the kind of thing it is`() {
        val session = UUID.randomUUID()

        val grounded = grounding.idsFor(turn(ResolvedPage(PageContextType.SESSION, session, "Standard group class")))

        assertEquals(setOf(session), grounded.sessions)
        assertTrue(grounded.notes.isEmpty() && grounded.figures.isEmpty())
    }

    @Test
    fun `a page the server could not resolve grounds nothing`() {
        val grounded = grounding.idsFor(turn(ResolvedPage(PageContextType.NOTE, UUID.randomUUID(), null)))

        assertTrue(grounded.notes.isEmpty())
    }

    @Test
    fun `an unreadable tool payload is skipped, not fatal`() {
        conversations.append(conversationId, AssistantRole.TOOL, "search", mutableMapOf("name" to "search", "result" to "garbage"))
        val figure = UUID.randomUUID()
        toolResult("figure" to figure)

        assertEquals(setOf(figure), grounding.idsFor(turn()).figures)
    }

    @Test
    fun `the scope hands the running turn to the tools, and only while it runs`() {
        val scope = AssistantTurnScope()
        val t = turn()

        assertNull(scope.current())
        val seen = scope.run(t) { scope.current() }

        assertNotNull(seen)
        assertEquals(t.conversationId, seen!!.conversationId)
        assertNull(scope.current(), "the turn is gone once the block returns")
    }

    @Test
    fun `the scope clears the turn even when the block throws`() {
        val scope = AssistantTurnScope()

        try { scope.run(turn()) { error("boom") } } catch (e: IllegalStateException) { /* expected */ }

        assertNull(scope.current())
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantGroundingTest"`
Expected: compile FAIL (`ToolTurn`, `AssistantTurnScope`, `AssistantGrounding` do not exist).

- [ ] **Step 3: Implement**

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantTurnScope.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.ResolvedPage
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What a tool needs to know about the turn it is running in: the conversation, the page the
 * user is on, and how many drafts each tool has already refused this turn.
 */
class ToolTurn(val conversationId: UUID, val page: ResolvedPage) {
    val failures: MutableMap<String, Int> = mutableMapOf()
}

/**
 * The tools are singletons but a turn is not, and Spring AI calls a tool with its arguments only.
 * The loop runs the tool calls on the request thread, so the turn rides a thread-local for the
 * length of [run]. A tool called with no turn running gets null and must refuse.
 */
@Component
@ConditionalOnAssistant
class AssistantTurnScope {

    private val current = ThreadLocal<ToolTurn?>()

    fun <T> run(turn: ToolTurn, block: () -> T): T {
        val previous = current.get()
        current.set(turn)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun current(): ToolTurn? = current.get()
}
```

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantGrounding.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.dto.ToolResult
import com.jankowski.rafal.dancebook.model.AssistantRole
import org.springframework.stereotype.Component
import java.util.UUID

/** The ids a draft may name, by what they are. */
data class Grounded(val figures: Set<UUID>, val notes: Set<UUID>, val sessions: Set<UUID>)

/**
 * The grounding rule: a figure, note or session id in a draft must have come from a tool result
 * in this conversation, or be the page the user is looking at (which the server resolved through
 * a service). Anything else is an id the model made up, or took from somewhere it should not.
 * Ids are kept by the kind of card that carried them, so a figure's id cannot stand in for a session's.
 */
@Component
@ConditionalOnAssistant
class AssistantGrounding(
    private val conversations: AssistantConversationService,
    private val objectMapper: ObjectMapper
) {

    fun idsFor(turn: ToolTurn): Grounded {
        val cards = conversations.messages(turn.conversationId)
            .filter { it.role == AssistantRole.TOOL }
            .mapNotNull { it.toolPayload?.get("result") }
            .flatMap { result ->
                try {
                    objectMapper.convertValue(result, ToolResult::class.java).items
                } catch (e: IllegalArgumentException) {
                    emptyList()
                }
            }

        fun ids(kind: String): MutableSet<UUID> =
            cards.filter { it.kind == kind }.mapNotNull { parse(it.id) }.toMutableSet()

        val figures = ids("figure")
        val notes = ids("note")
        val sessions = ids("session")

        val page = turn.page
        if (page.name != null && page.id != null) {
            when (page.type) {
                PageContextType.FIGURE -> figures += page.id
                PageContextType.NOTE -> notes += page.id
                PageContextType.SESSION -> sessions += page.id
                else -> Unit
            }
        }
        return Grounded(figures, notes, sessions)
    }

    private fun parse(raw: String): UUID? = try { UUID.fromString(raw) } catch (e: IllegalArgumentException) { null }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantGroundingTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantTurnScope.kt src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantGrounding.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantGroundingTest.kt
git commit -m "feat: ground draft ids in this conversation's tool results (#149)"
```

---

### Task 4: The draft tools

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftService.kt` (interface only, implemented in Task 5)
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftTools.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDtos.kt` (add `draftId` to `ToolResult`)
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/FakeAssistantDraftService.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftToolsTest.kt`

**Interfaces:**
- Consumes: `AssistantTurnScope.current()`, `AssistantGrounding.idsFor(turn)`, `AssistantDraftCodec.toPayload`, a `jakarta.validation.Validator`, `DanceTypeService.findAll()`, `DanceCategoryService.findAll()`, `ActiveCalendarService.creationTarget()`.
- Produces:
  - `interface AssistantDraftService` (all nine methods; `AssistantDraftServiceImpl` in Task 5):
    `create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): UUID`, `attach(draftId: UUID, messageId: UUID)`, `view(id: UUID): DraftView`, `views(ids: Collection<UUID>): List<DraftView>`, `save(id: UUID): DraftView`, `openInForm(id: UUID): String`, `noteForForm(id: UUID): MaterialRequest?`, `sessionForForm(id: UUID): TrainingEventRequest?`, `figureForForm(id: UUID): DanceFigureRequest?`
  - `ToolResult(total, items, message, draftId: String? = null)`
  - `AssistantDraftTools.callbacks: List<ToolCallback>` with tools `draft_note`, `draft_training_event`, `draft_figure`
  - `class DraftSegmentArg { var category: String; var minutes: Int }`
  - Test fake `FakeAssistantDraftService` (`created`, `attached`)

- [ ] **Step 1: Extend `ToolResult` and write the service interface**

In `src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDtos.kt`, replace:

```kotlin
data class ToolResult(
    val total: Int,
    val items: List<ResultCard>,
    val message: String? = null
)
```

with:

```kotlin
data class ToolResult(
    val total: Int,
    val items: List<ResultCard>,
    val message: String? = null,
    /** Set by the draft tools: the id of the draft the turn created, which the reply shows as a card. */
    val draftId: String? = null
)
```

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftService.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.DraftKind
import java.util.UUID

/**
 * What the rest of the assistant does with drafts. Every method is scoped to the current user:
 * someone else's draft is an `EntityNotFoundException` (a 404), never a 403.
 */
interface AssistantDraftService {

    /** Stores a validated draft for [conversationId] and returns its id. Writes nothing else. */
    fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): UUID

    /** Ties the draft to the tool message that produced it. */
    fun attach(draftId: UUID, messageId: UUID)

    fun view(id: UUID): DraftView

    /** The cards for [ids], skipping any that no longer exist. */
    fun views(ids: Collection<UUID>): List<DraftView>

    /**
     * Saves the draft through the same service the form calls. Returns the card as it is afterwards:
     * `SAVED` with a link, or still `PENDING` with the error in `notice`. A draft that is not
     * pending throws [DraftNotPendingException] and nothing is written.
     */
    fun save(id: UUID): DraftView

    /** "Edit in form": marks the draft discarded and returns the create form's URL, prefilled from it. */
    fun openInForm(id: UUID): String

    /** The draft as the create form's request, or null when it is missing, foreign, already saved or of another kind. */
    fun noteForForm(id: UUID): MaterialRequest?
    fun sessionForForm(id: UUID): TrainingEventRequest?
    fun figureForForm(id: UUID): DanceFigureRequest?
}
```

- [ ] **Step 2: Write the fake and the failing tool tests**

`src/test/kotlin/com/jankowski/rafal/dancebook/service/FakeAssistantDraftService.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import java.util.UUID

/** Records what the tools draft, so a test can read it back. Only what the tools and the loop call is implemented. */
class FakeAssistantDraftService : AssistantDraftService {

    data class Created(val id: UUID, val kind: DraftKind, val payload: MutableMap<String, Any?>)

    val created = mutableListOf<Created>()
    val attached = mutableMapOf<UUID, UUID>()

    override fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): UUID {
        val id = UUID.randomUUID()
        created += Created(id, kind, payload)
        return id
    }

    override fun attach(draftId: UUID, messageId: UUID) { attached[draftId] = messageId }

    override fun view(id: UUID): DraftView {
        val draft = created.first { it.id == id }
        val heading = (draft.payload["name"] ?: draft.payload["title"] ?: "Draft").toString()
        return DraftView(id, draft.kind, DraftStatus.PENDING, heading, emptyList(), emptyList(), null, null)
    }

    override fun views(ids: Collection<UUID>): List<DraftView> = ids.filter { id -> created.any { it.id == id } }.map { view(it) }

    override fun save(id: UUID): DraftView = error("not used by these tests")
    override fun openInForm(id: UUID): String = error("not used by these tests")
    override fun noteForForm(id: UUID): MaterialRequest? = null
    override fun sessionForForm(id: UUID): TrainingEventRequest? = null
    override fun figureForForm(id: UUID): DanceFigureRequest? = null
}
```

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftToolsTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.dto.ResolvedPage
import com.jankowski.rafal.dancebook.dto.ToolResult
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftToolsTest {

    private val user = AppUser().apply { id = UUID.randomUUID(); displayName = "Rafał" }
    private val mapper = DraftTestSupport.mapper()
    private val standard = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val waltz = DanceType().apply { id = UUID.randomUUID(); name = "Waltz"; category = standard }
    private val calendar = TrainingCalendar().apply { id = UUID.randomUUID() }

    private val figureId = UUID.randomUUID()
    private val sessionId = UUID.randomUUID()
    private val noteId = UUID.randomUUID()

    private val turns = AssistantTurnScope()
    private lateinit var conversations: FakeAssistantConversationService
    private lateinit var drafts: FakeAssistantDraftService
    private lateinit var tools: AssistantDraftTools
    private lateinit var turn: ToolTurn
    private lateinit var activeCalendarService: ActiveCalendarService

    @BeforeEach
    fun setUp() {
        conversations = FakeAssistantConversationService(user)
        drafts = FakeAssistantDraftService()
        val danceTypeService = mock(DanceTypeService::class.java)
        `when`(danceTypeService.findAll()).thenReturn(listOf(waltz))
        val danceCategoryService = mock(DanceCategoryService::class.java)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(standard))
        activeCalendarService = mock(ActiveCalendarService::class.java)
        `when`(activeCalendarService.creationTarget()).thenReturn(calendar)
        tools = AssistantDraftTools(
            turns, AssistantGrounding(conversations, mapper), drafts, AssistantDraftCodec(mapper),
            DraftTestSupport.validator(), danceTypeService, danceCategoryService, activeCalendarService
        )
        turn = ToolTurn(conversations.start("wrap up").id!!, ResolvedPage(PageContextType.HOME, null, null))
    }

    private fun ground(kind: String, id: UUID) {
        conversations.append(
            turn.conversationId, AssistantRole.TOOL, "search",
            mutableMapOf(
                "name" to "search",
                "result" to mutableMapOf(
                    "total" to 1,
                    "items" to listOf(mutableMapOf<String, Any?>("kind" to kind, "id" to id.toString(), "title" to "t", "url" to "/x", "chips" to emptyList<String>()))
                )
            )
        )
    }

    private fun <T> inTurn(block: () -> T): T = turns.run(turn, block)

    private fun note(
        title: String = "Tuesday class", text: String? = "Feather step. Head drops.", style: String? = "Waltz",
        figures: List<String>? = listOf(figureId.toString()), session: String? = sessionId.toString(),
        attended: Boolean? = true, video: String? = null
    ): ToolResult = inTurn { tools.draftNote(title, text, style, figures, session, attended, video) }

    // ── draft_note ─────────────────────────────────────────────────────────

    @Test
    fun `a note draft with grounded ids is stored as a pending draft and nothing else happens`() {
        ground("figure", figureId)
        ground("session", sessionId)

        val result = note()

        val created = drafts.created.single()
        assertEquals(DraftKind.NOTE, created.kind)
        assertEquals(created.id.toString(), result.draftId)
        val request = mapper.convertValue(created.payload, MaterialRequest::class.java)
        assertEquals("Tuesday class", request.name)
        assertEquals("<div>Feather step. Head drops.</div>", request.description)
        assertEquals(waltz.id, request.danceTypeId)
        assertEquals(standard.id, request.danceCategoryId)
        assertEquals(listOf(figureId), request.figureIds)
        assertEquals(sessionId, request.trainingEventId)
        assertTrue(request.markAttended)
        assertNull(result.message?.takeIf { it.contains("Left out") })
    }

    @Test
    fun `an id the model invented is dropped, the rest of the draft is kept, and the model is told`() {
        ground("figure", figureId)
        val invented = UUID.randomUUID()

        val result = note(figures = listOf(figureId.toString(), invented.toString()), session = null, attended = false)

        val request = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java)
        assertEquals(listOf(figureId), request.figureIds)
        assertTrue(result.message!!.contains("Left out"), result.message)
    }

    @Test
    fun `a figure id that is not even a uuid is dropped, not fatal`() {
        val result = note(figures = listOf("natural-turn"), session = null, attended = false)

        val request = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java)
        assertTrue(request.figureIds.isEmpty())
        assertNotNull(result.draftId)
        assertTrue(result.message!!.contains("natural-turn"), result.message)
    }

    @Test
    fun `a session id that no tool returned is dropped, and markAttended goes with it`() {
        val result = note(session = UUID.randomUUID().toString(), figures = null)

        val request = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java)
        assertNull(request.trainingEventId)
        assertFalse(request.markAttended)
        assertTrue(result.message!!.contains("markAttended"), result.message)
    }

    @Test
    fun `an id from the same round as its search is not stored yet, so it is dropped`() {
        // The search result is stored after the round ends, so a draft in that round cannot cite it.
        val result = note(figures = listOf(figureId.toString()), session = null, attended = false)

        assertTrue(mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).figureIds.isEmpty())
        assertTrue(result.message!!.contains("tool result"), result.message)
    }

    @Test
    fun `the session the user is looking at counts as grounded`() {
        turn = ToolTurn(turn.conversationId, ResolvedPage(PageContextType.SESSION, sessionId, "Standard group class"))

        note(figures = null)

        assertEquals(sessionId, mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).trainingEventId)
    }

    @Test
    fun `a title that is too short gets no draft and one chance to fix it, then an instruction to ask the user`() {
        val first = note(title = "x", session = null, figures = null, attended = false)
        assertNull(first.draftId)
        assertTrue(first.message!!.contains("once more"), first.message)

        val second = note(title = "y", session = null, figures = null, attended = false)
        assertNull(second.draftId)
        assertTrue(second.message!!.contains("Do not call draft_note again"), second.message)
        assertTrue(drafts.created.isEmpty())
    }

    @Test
    fun `an unknown dance style is a problem for the model to fix, not a silent drop`() {
        val result = note(style = "Lindy Hop", session = null, figures = null, attended = false)

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("Lindy Hop"), result.message)
    }

    @Test
    fun `markup in the note text is escaped`() {
        note(text = "<script>alert(1)</script>", session = null, figures = null, attended = false)

        val description = mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).description!!
        assertFalse(description.contains("<script>"), description)
    }

    @Test
    fun `a tool called outside a turn refuses instead of guessing a conversation`() {
        val result = tools.draftNote("Tuesday class", null, null, null, null, null, null)

        assertNull(result.draftId)
        assertTrue(drafts.created.isEmpty())
    }

    // ── draft_training_event ───────────────────────────────────────────────

    private fun session(
        title: String = "Practice", date: String = "2026-10-03", start: String = "10:00", end: String = "12:00",
        type: String? = null, segments: List<DraftSegmentArg>? = null, description: String? = null, note: String? = null
    ): ToolResult = inTurn { tools.draftTrainingEvent(title, date, start, end, type, segments, description, note) }

    @Test
    fun `a session draft carries the date, times, calendar, styles and a grounded note`() {
        ground("note", noteId)

        val result = session(
            segments = listOf(DraftSegmentArg().apply { category = "standard"; minutes = 60 }),
            description = "Quickstep then tango", note = noteId.toString()
        )

        val created = drafts.created.single()
        assertEquals(DraftKind.TRAINING_EVENT, created.kind)
        assertEquals(created.id.toString(), result.draftId)
        val request = mapper.convertValue(created.payload, TrainingEventRequest::class.java)
        assertEquals(LocalDate.of(2026, 10, 3), request.date)
        assertEquals(LocalTime.of(10, 0), request.startTime)
        assertEquals(LocalTime.of(12, 0), request.endTime)
        assertEquals("TRAINING", request.eventType)
        assertEquals(calendar.id, request.calendarId)
        assertEquals(standard.id, request.segments.single().categoryId)
        assertEquals(60, request.segments.single().durationMinutes)
        assertEquals(noteId, request.materialId)
        assertEquals("NONE", request.repeat)
    }

    @Test
    fun `an end time that is not after the start is refused with a reason`() {
        val result = session(start = "12:00", end = "10:00")

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("endTime"), result.message)
    }

    @Test
    fun `an event type outside the enum is refused and the valid ones are listed`() {
        val result = session(type = "PARTY")

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("TRAINING") && result.message.contains("CAMP"), result.message)
    }

    @Test
    fun `a date that is not a real day is refused`() {
        val result = session(date = "next Saturday")

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("yyyy-MM-dd"), result.message)
    }

    @Test
    fun `an unknown segment category is refused and the real ones are listed`() {
        val result = session(segments = listOf(DraftSegmentArg().apply { category = "Polka"; minutes = 30 }))

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("Standard"), result.message)
    }

    @Test
    fun `no writable calendar means no draft, and the reason reaches the model without using up its retry`() {
        `when`(activeCalendarService.creationTarget()).thenThrow(CalendarSyncException("The active calendar is disabled"))

        val result = session()

        assertNull(result.draftId)
        assertTrue(result.message!!.contains("disabled"), result.message)
        assertTrue(turn.failures.isEmpty())
    }

    // ── draft_figure ───────────────────────────────────────────────────────

    @Test
    fun `a figure draft needs a known dance style and may carry a class and timing`() {
        val result = inTurn { tools.draftFigure("Heel Turn", "Waltz", "D", "1 2 3", "Facing DW", "Facing LOD", "Turn on the heel") }

        val created = drafts.created.single()
        assertEquals(DraftKind.FIGURE, created.kind)
        assertEquals(created.id.toString(), result.draftId)
        val request = mapper.convertValue(created.payload, DanceFigureRequest::class.java)
        assertEquals("Heel Turn", request.name)
        assertEquals(waltz.id, request.danceTypeId)
        assertEquals(DanceClass.D, request.danceClass)
        assertEquals("1 2 3", request.alternativeTiming)
    }

    @Test
    fun `a figure draft with an unknown style or class is refused`() {
        assertNull(inTurn { tools.draftFigure("Heel Turn", "Lindy Hop", null, null, null, null, null) }.draftId)
        assertNull(inTurn { tools.draftFigure("Heel Turn", "Waltz", "Z", null, null, null, null) }.draftId)
        assertTrue(drafts.created.isEmpty())
    }

    // ── through Spring AI's own argument parsing ───────────────────────────

    @Test
    fun `the session tool is callable with the JSON a model sends, nested segments included`() {
        val callback = tools.callbacks.first { it.toolDefinition.name() == "draft_training_event" }
        assertTrue(callback.toolDefinition.inputSchema().contains("minutes"), callback.toolDefinition.inputSchema())

        val json = inTurn {
            callback.call(
                """{"title":"Practice","date":"2026-10-03","startTime":"10:00","endTime":"12:00",""" +
                    """"segments":[{"category":"Standard","minutes":60}]}"""
            )
        }

        assertTrue(json.contains("draftId"), json)
        val request = mapper.convertValue(drafts.created.single().payload, TrainingEventRequest::class.java)
        assertEquals(60, request.segments.single().durationMinutes)
    }

    @Test
    fun `all three tools are offered to the model by name`() {
        assertEquals(
            setOf("draft_note", "draft_training_event", "draft_figure"),
            tools.callbacks.map { it.toolDefinition.name() }.toSet()
        )
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftToolsTest"`
Expected: compile FAIL (`AssistantDraftTools`, `DraftSegmentArg` do not exist).

- [ ] **Step 4: Implement the tools**

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftTools.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.ToolResult
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.TrainingEventType
import jakarta.validation.Validator
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** One style row of a drafted session. A class with defaults, so Spring AI can build it from the model's JSON. */
class DraftSegmentArg {
    var category: String = ""
    var minutes: Int = 0
}

/**
 * The assistant's draft tools. **None of them writes to a domain table**: each checks the
 * arguments, builds the same request DTO the form binds, validates it with the forms' Bean
 * Validation, and stores it as a pending draft. Only the card's Save writes, and it goes through
 * the services (see [AssistantDraftService.save]).
 *
 * Like the read tools, none throws: a problem comes back as a [ToolResult] message the model can
 * act on, because an exception would end the whole turn. A draft that fails validation may be
 * retried once; the second failure tells the model to ask the user instead.
 */
@Component
@ConditionalOnAssistant
class AssistantDraftTools(
    private val turns: AssistantTurnScope,
    private val grounding: AssistantGrounding,
    private val drafts: AssistantDraftService,
    private val codec: AssistantDraftCodec,
    private val validator: Validator,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val activeCalendarService: ActiveCalendarService
) {

    companion object {
        private const val NOTE = "draft_note"
        private const val SESSION = "draft_training_event"
        private const val FIGURE = "draft_figure"
        private const val MAX_ATTEMPTS = 2
    }

    val callbacks: List<ToolCallback> by lazy { ToolCallbacks.from(this).toList() }

    @Tool(
        name = NOTE,
        description = "Draft a new note for the user to review. Nothing is saved until the user presses Save on the card. " +
            "Every id must come from an earlier search_figures, search_notes, list_sessions or get_* result in this conversation; " +
            "an id you did not get from a tool is dropped. To wrap up a session, pass its sessionId and markAttended true."
    )
    fun draftNote(
        @ToolParam(description = "Short title of the note, at least 2 characters") title: String,
        @ToolParam(description = "The note's text as plain text; blank lines separate paragraphs", required = false) text: String?,
        @ToolParam(description = "Dance style name, for example Waltz", required = false) danceStyle: String?,
        @ToolParam(description = "Ids of catalog figures to pin, taken from search_figures or get_figure results", required = false) figureIds: List<String>?,
        @ToolParam(description = "Id of the training session the note is about, taken from list_sessions results", required = false) sessionId: String?,
        @ToolParam(description = "Mark that session as attended. Needs sessionId", required = false) markAttended: Boolean?,
        @ToolParam(description = "A video link for the note", required = false) videoLink: String?
    ): ToolResult {
        val turn = turns.current() ?: return unavailable()
        val grounded = grounding.idsFor(turn)
        val style = findStyle(danceStyle)
        if (style is Style.Unknown) return fail(turn, NOTE, listOf(style.problem))

        val dropped = mutableListOf<String>()
        val pins = figureIds.orEmpty().mapNotNull { keep(it, "figure", grounded.figures, dropped) }.distinct()
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { keep(it, "session", grounded.sessions, dropped) }
        val attend = markAttended == true && session != null
        if (markAttended == true && session == null) dropped += "markAttended (it needs a valid sessionId)"

        val type = (style as? Style.Found)?.type
        val request = MaterialRequest(
            name = title.trim(),
            description = AssistantText.toRichText(text),
            danceCategoryId = type?.category?.id,
            danceTypeId = type?.id,
            videoLink = videoLink?.trim()?.takeIf { it.isNotEmpty() },
            version = 0,
            trainingEventId = session,
            figureIds = pins,
            markAttended = attend
        )
        return store(turn, DraftKind.NOTE, NOTE, request, dropped)
    }

    @Tool(
        name = SESSION,
        description = "Draft a new training session for the user to review; it is added to their Google Calendar only when they press Save. " +
            "For one session only, not a repeating series. The times are local. A note to link must come from an earlier tool result."
    )
    fun draftTrainingEvent(
        @ToolParam(description = "Title of the session, for example Practice") title: String,
        @ToolParam(description = "Day, formatted yyyy-MM-dd") date: String,
        @ToolParam(description = "Start time, formatted HH:mm (24 hours)") startTime: String,
        @ToolParam(description = "End time on the same day, formatted HH:mm, after the start") endTime: String,
        @ToolParam(description = "TRAINING, CAMP, COMPETITION, WORKSHOP or OTHER. Defaults to TRAINING", required = false) eventType: String?,
        @ToolParam(description = "How the session splits across dance categories, for example Standard 60 minutes then Latin 30", required = false) segments: List<DraftSegmentArg>?,
        @ToolParam(description = "Free text about the session, for example the dances planned", required = false) description: String?,
        @ToolParam(description = "Id of a note to link to the session, taken from search_notes or get_note results", required = false) noteId: String?
    ): ToolResult {
        val turn = turns.current() ?: return unavailable()
        val grounded = grounding.idsFor(turn)

        val day = parseDate(date) ?: return fail(turn, SESSION, listOf("date '$date' is not a real day; use yyyy-MM-dd"))
        val start = parseTime(startTime) ?: return fail(turn, SESSION, listOf("startTime '$startTime' is not a time; use HH:mm"))
        val end = parseTime(endTime) ?: return fail(turn, SESSION, listOf("endTime '$endTime' is not a time; use HH:mm"))
        if (!end.isAfter(start)) return fail(turn, SESSION, listOf("endTime must be after startTime on the same day"))

        val kind = eventType?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: TrainingEventType.TRAINING.name
        if (TrainingEventType.entries.none { it.name == kind }) {
            return fail(turn, SESSION, listOf("eventType '$eventType' is not valid; use one of ${TrainingEventType.entries.joinToString { it.name }}"))
        }

        val categories = danceCategoryService.findAll()
        val rows = mutableListOf<TrainingEventSegmentRequest>()
        for (segment in segments.orEmpty()) {
            val category = categories.firstOrNull { it.name.equals(segment.category.trim(), ignoreCase = true) }
                ?: return fail(turn, SESSION, listOf("there is no dance category '${segment.category}'; use one of ${categories.joinToString { it.name }}"))
            rows += TrainingEventSegmentRequest(category.id, segment.minutes)
        }

        val dropped = mutableListOf<String>()
        val note = noteId?.takeIf { it.isNotBlank() }?.let { keep(it, "note", grounded.notes, dropped) }

        val target = try {
            activeCalendarService.creationTarget()
        } catch (e: CalendarSyncException) {
            // Not something the model can fix by retrying, so it does not use up the retry.
            return ToolResult(0, emptyList(), "No draft was created: ${e.message}. Tell the user; they need to fix their calendar first.")
        }

        val request = TrainingEventRequest(
            title = title.trim(),
            date = day,
            startTime = start,
            endTime = end,
            eventType = kind,
            calendarId = target.id,
            segments = rows,
            description = AssistantText.toRichText(description),
            materialId = note
        )
        return store(turn, DraftKind.TRAINING_EVENT, SESSION, request, dropped)
    }

    @Tool(
        name = FIGURE,
        description = "Draft a new catalog figure for the user to review. Nothing is saved until the user presses Save. " +
            "Check with search_figures first that it is not already in the catalog."
    )
    fun draftFigure(
        @ToolParam(description = "The figure's name, for example Heel Turn") name: String,
        @ToolParam(description = "Dance style name, for example Waltz") danceStyle: String,
        @ToolParam(description = "Syllabus class letter from H to S", required = false) danceClass: String?,
        @ToolParam(description = "The timing, for example 1 2 3 or S Q Q", required = false) alternativeTiming: String?,
        @ToolParam(description = "Starting position, for example Facing diagonal wall", required = false) startingPosition: String?,
        @ToolParam(description = "Ending position", required = false) endingPosition: String?,
        @ToolParam(description = "Notes about the figure", required = false) notes: String?
    ): ToolResult {
        val turn = turns.current() ?: return unavailable()
        val style = findStyle(danceStyle)
        val type = (style as? Style.Found)?.type
            ?: return fail(turn, FIGURE, listOf((style as? Style.Unknown)?.problem ?: "danceStyle is required"))
        val cls = danceClass?.trim()?.takeIf { it.isNotEmpty() }?.let { letter ->
            DanceClass.entries.firstOrNull { it.name.equals(letter.removePrefix("Class").removePrefix("class").trim(), ignoreCase = true) }
                ?: return fail(turn, FIGURE, listOf("there is no syllabus class '$danceClass'; use a letter from H to S"))
        }

        val request = DanceFigureRequest(
            name = name.trim(),
            danceTypeId = type.id,
            danceClass = cls,
            alternativeTiming = alternativeTiming?.trim()?.takeIf { it.isNotEmpty() },
            startingPosition = startingPosition?.trim()?.takeIf { it.isNotEmpty() },
            endingPosition = endingPosition?.trim()?.takeIf { it.isNotEmpty() },
            notes = AssistantText.toRichText(notes)
        )
        return store(turn, DraftKind.FIGURE, FIGURE, request, emptyList())
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private sealed interface Style {
        data object None : Style
        data class Found(val type: DanceType) : Style
        data class Unknown(val problem: String) : Style
    }

    private fun findStyle(name: String?): Style {
        if (name.isNullOrBlank()) return Style.None
        val match = danceTypeService.findAll().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        return if (match != null) Style.Found(match) else Style.Unknown("there is no dance style called '${name.trim()}'")
    }

    /** The id, if the model's [raw] text is a UUID that a tool result in this conversation contained. Else it is noted in [dropped]. */
    private fun keep(raw: String, what: String, allowed: Set<UUID>, dropped: MutableList<String>): UUID? {
        val id = try { UUID.fromString(raw.trim()) } catch (e: IllegalArgumentException) { null }
        if (id == null || id !in allowed) {
            dropped += "the $what id '${raw.trim().take(40)}' (it did not come from a tool result in this conversation; search for it first)"
            return null
        }
        return id
    }

    private fun parseDate(text: String): LocalDate? = try { LocalDate.parse(text.trim()) } catch (e: DateTimeParseException) { null }

    private fun parseTime(text: String): LocalTime? = try { LocalTime.parse(text.trim()) } catch (e: DateTimeParseException) { null }

    private fun store(turn: ToolTurn, kind: DraftKind, tool: String, request: Any, dropped: List<String>): ToolResult {
        val problems = validator.validate(request).map { "${it.propertyPath}: ${it.message}" }.sorted()
        if (problems.isNotEmpty()) return fail(turn, tool, problems)
        val id = drafts.create(turn.conversationId, kind, codec.toPayload(request))
        val left = if (dropped.isEmpty()) "" else " Left out: ${dropped.joinToString("; ")}."
        return ToolResult(
            1, emptyList(),
            "Draft created. The user now sees it as a card with Save and Edit in form; nothing is saved yet. " +
                "Say so in one sentence and do not repeat the fields.$left",
            draftId = id.toString()
        )
    }

    private fun fail(turn: ToolTurn, tool: String, problems: List<String>): ToolResult {
        val attempts = turn.failures.merge(tool, 1, Int::plus) ?: 1
        val advice = if (attempts >= MAX_ATTEMPTS) {
            "Do not call $tool again. Tell the user what is missing or wrong and ask them."
        } else {
            "Fix these and call $tool once more."
        }
        return ToolResult(0, emptyList(), "No draft was created: ${problems.joinToString("; ")}. $advice")
    }

    private fun unavailable() = ToolResult(0, emptyList(), "Drafts are not available right now.")
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftToolsTest"`
Expected: PASS (19 tests). If `the session tool is callable with the JSON a model sends` fails on parsing `segments`, `DraftSegmentArg` is not deserialising: keep it a plain class with `var` fields and defaults.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftService.kt src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftTools.kt src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDtos.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/FakeAssistantDraftService.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftToolsTest.kt
git commit -m "feat: add the draft_note, draft_training_event and draft_figure tools (#149)"
```

---

### Task 5: Draft cards' data and Save

**Files:**
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftViews.kt`
- Create: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceImpl.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftViewsTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceImplTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 1, 2 and 4 types; `MaterialService.create/addFigure/findById`, `TrainingEventService.create/updateAttendance/bulkUpdateMaterial/findById`, `DanceFigureService.create/findById`, `ActiveCalendarService.validateCreationTarget`, `DanceTypeService.findById`, `DanceCategoryService.findById`, `RichTextService.toPlainText`, `TransactionTemplate`.
- Produces:
  - `AssistantDraftViews.build(draft: AssistantDraft): DraftView`
  - `AssistantDraftServiceImpl : AssistantDraftService` (all nine methods)
  - Save order for a note: one DB transaction for `MaterialService.create` → `MaterialService.addFigure` per figure → `TrainingEventService.updateAttendance(ATTENDED)` when `markAttended`; then, outside it, the session link through `TrainingEventService.bulkUpdateMaterial` (a failure there is a `notice` on a saved draft, as on the form, not a rollback).

- [ ] **Step 1: Write the failing views test**

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftViewsTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventSegmentRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceClass
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.TrainingEvent
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftViewsTest {

    private val mapper = DraftTestSupport.mapper()
    private val codec = AssistantDraftCodec(mapper)
    private val standard = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val waltz = DanceType().apply { id = UUID.randomUUID(); name = "Waltz"; category = standard }
    private val figure = DanceFigure().apply { id = UUID.randomUUID(); name = "Feather Step"; alternativeTiming = "S Q Q" }
    private val session = TrainingEvent().apply { id = UUID.randomUUID(); title = "Standard group class" }

    private lateinit var views: AssistantDraftViews
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var trainingEventService: TrainingEventService

    @BeforeEach
    fun setUp() {
        val danceTypeService = mock(DanceTypeService::class.java)
        `when`(danceTypeService.findById(waltz.id!!)).thenReturn(waltz)
        val danceCategoryService = mock(DanceCategoryService::class.java)
        `when`(danceCategoryService.findById(standard.id!!)).thenReturn(standard)
        danceFigureService = mock(DanceFigureService::class.java)
        `when`(danceFigureService.findById(figure.id!!)).thenReturn(figure)
        trainingEventService = mock(TrainingEventService::class.java)
        `when`(trainingEventService.findById(session.id!!)).thenReturn(session)
        views = AssistantDraftViews(
            codec, RichTextServiceImpl(), danceTypeService, danceCategoryService, danceFigureService,
            mock(MaterialService::class.java), trainingEventService
        )
    }

    private fun draft(kind: DraftKind, request: Any, status: DraftStatus = DraftStatus.PENDING) = AssistantDraft().apply {
        id = UUID.randomUUID(); this.kind = kind; payload = codec.toPayload(request); this.status = status
    }

    @Test
    fun `a note card shows every field, the session, the attendance, and each figure with its timing`() {
        val view = views.build(draft(DraftKind.NOTE, MaterialRequest(
            name = "Tuesday class", description = "<div>Head drops.</div>", danceTypeId = waltz.id, version = 0,
            trainingEventId = session.id, figureIds = listOf(figure.id!!), markAttended = true
        )))

        assertEquals("Tuesday class", view.heading)
        val fields = view.fields.associate { it.label to it.value }
        assertEquals("Head drops.", fields["Text"])
        assertEquals("Waltz", fields["Dance style"])
        assertEquals("Standard group class", fields["Session"])
        assertEquals("Mark the session as attended", fields["Attendance"])
        assertEquals("Feather Step", view.figures.single().name)
        assertEquals("S Q Q", view.figures.single().timing)
        assertEquals("/dance-figures/${figure.id}", view.figures.single().url)
    }

    @Test
    fun `a figure the viewer can no longer see is left off the card rather than failing it`() {
        val gone = UUID.randomUUID()
        `when`(danceFigureService.findById(gone)).thenThrow(EntityNotFoundException("gone"))

        val view = views.build(draft(DraftKind.NOTE, MaterialRequest(name = "Tuesday class", version = 0, figureIds = listOf(gone, figure.id!!))))

        assertEquals(listOf("Feather Step"), view.figures.map { it.name })
    }

    @Test
    fun `a session card shows the day, the time, the style split and the type`() {
        val view = views.build(draft(DraftKind.TRAINING_EVENT, TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0),
            segments = mutableListOf(TrainingEventSegmentRequest(standard.id, 60)), description = "<div>Quickstep then tango</div>"
        )))

        assertEquals("Practice", view.heading)
        val fields = view.fields.associate { it.label to it.value }
        assertEquals("Sat 3 Oct 2026", fields["Date"])
        assertEquals("10:00–12:00", fields["Time"])
        assertEquals("Training", fields["Type"])
        assertEquals("Standard 60m", fields["Styles"])
        assertEquals("Quickstep then tango", fields["Notes"])
    }

    @Test
    fun `a figure card shows its style, class and timing, and skips what is empty`() {
        val view = views.build(draft(DraftKind.FIGURE, DanceFigureRequest(
            name = "Heel Turn", danceTypeId = waltz.id, danceClass = DanceClass.D, alternativeTiming = "1 2 3"
        )))

        val fields = view.fields.associate { it.label to it.value }
        assertEquals("Waltz", fields["Dance style"])
        assertTrue(fields["Class"]!!.contains("D"), fields.toString())
        assertEquals("1 2 3", fields["Timing"])
        assertTrue("Notes" !in fields)
    }

    @Test
    fun `a saved draft links to what it created, for each kind, and a pending one does not`() {
        val created = UUID.randomUUID()
        val note = draft(DraftKind.NOTE, MaterialRequest(name = "x y", version = 0), DraftStatus.SAVED).apply { savedEntityId = created }
        val event = draft(DraftKind.TRAINING_EVENT, TrainingEventRequest(title = "x"), DraftStatus.SAVED).apply { savedEntityId = created }
        val fig = draft(DraftKind.FIGURE, DanceFigureRequest(name = "x"), DraftStatus.SAVED).apply { savedEntityId = created }

        assertEquals("/materials/$created", views.build(note).savedUrl)
        assertEquals("/training-events/$created", views.build(event).savedUrl)
        assertEquals("/dance-figures/$created", views.build(fig).savedUrl)
        assertNull(views.build(draft(DraftKind.FIGURE, DanceFigureRequest(name = "x"))).savedUrl)
    }

    @Test
    fun `the notice travels to the card`() {
        val failed = draft(DraftKind.FIGURE, DanceFigureRequest(name = "x")).apply { notice = "Name is too short" }

        assertEquals("Name is too short", views.build(failed).notice)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftViewsTest"`
Expected: compile FAIL (`AssistantDraftViews` does not exist).

- [ ] **Step 3: Implement the views**

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftViews.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftField
import com.jankowski.rafal.dancebook.dto.DraftFigureLine
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Builds what a draft card shows from the draft's payload. Names are looked up through the
 * services, as the viewer, and a lookup that fails (the thing was deleted, or is hidden from
 * this user) leaves that line off the card: a card must always render.
 */
@Component
@ConditionalOnAssistant
class AssistantDraftViews(
    private val codec: AssistantDraftCodec,
    private val richTextService: RichTextService,
    private val danceTypeService: DanceTypeService,
    private val danceCategoryService: DanceCategoryService,
    private val danceFigureService: DanceFigureService,
    private val materialService: MaterialService,
    private val trainingEventService: TrainingEventService
) {

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /** Where a saved draft's card links to. */
        fun urlFor(kind: DraftKind, id: UUID): String = when (kind) {
            DraftKind.NOTE -> "/materials/$id"
            DraftKind.TRAINING_EVENT -> "/training-events/$id"
            DraftKind.FIGURE -> "/dance-figures/$id"
        }
    }

    fun build(draft: AssistantDraft): DraftView {
        val savedUrl = draft.savedEntityId?.takeIf { draft.status == DraftStatus.SAVED }?.let { urlFor(draft.kind, it) }
        val (heading, fields, figures) = when (draft.kind) {
            DraftKind.NOTE -> note(codec.read(draft.payload, MaterialRequest::class.java))
            DraftKind.TRAINING_EVENT -> session(codec.read(draft.payload, TrainingEventRequest::class.java))
            DraftKind.FIGURE -> figure(codec.read(draft.payload, DanceFigureRequest::class.java))
        }
        return DraftView(draft.id!!, draft.kind, draft.status, heading, fields, figures, savedUrl, draft.notice)
    }

    private fun note(r: MaterialRequest): Triple<String, List<DraftField>, List<DraftFigureLine>> {
        val fields = mutableListOf<DraftField>()
        plain(r.description)?.let { fields += DraftField("Text", it) }
        r.danceTypeId?.let { id -> lookup { danceTypeService.findById(id).name } }?.let { fields += DraftField("Dance style", it) }
        r.trainingEventId?.let { id -> lookup { trainingEventService.findById(id).title } }?.let { fields += DraftField("Session", it) }
        if (r.markAttended) fields += DraftField("Attendance", "Mark the session as attended")
        r.videoLink?.let { fields += DraftField("Video", it) }
        val figures = r.figureIds.mapNotNull { id ->
            lookup { danceFigureService.findById(id) }?.let {
                DraftFigureLine(it.name, it.alternativeTiming?.takeIf { timing -> timing.isNotBlank() }, "/dance-figures/$id")
            }
        }
        return Triple(r.name, fields, figures)
    }

    private fun session(r: TrainingEventRequest): Triple<String, List<DraftField>, List<DraftFigureLine>> {
        val fields = mutableListOf<DraftField>()
        r.date?.let { fields += DraftField("Date", it.format(DAY)) }
        if (r.startTime != null && r.endTime != null) {
            fields += DraftField("Time", "${r.startTime.format(TIME)}–${r.endTime.format(TIME)}")
        }
        fields += DraftField("Type", r.eventType.lowercase().replaceFirstChar { it.uppercase() })
        val styles = r.segments.mapNotNull { s ->
            val id = s.categoryId ?: return@mapNotNull null
            lookup { danceCategoryService.findById(id).name }?.let { name -> "$name ${s.durationMinutes ?: 0}m" }
        }
        if (styles.isNotEmpty()) fields += DraftField("Styles", styles.joinToString(", "))
        r.materialId?.let { id -> lookup { materialService.findById(id).name } }?.let { fields += DraftField("Linked note", it) }
        plain(r.description)?.let { fields += DraftField("Notes", it) }
        return Triple(r.title, fields, emptyList())
    }

    private fun figure(r: DanceFigureRequest): Triple<String, List<DraftField>, List<DraftFigureLine>> {
        val fields = mutableListOf<DraftField>()
        r.danceTypeId?.let { id -> lookup { danceTypeService.findById(id).name } }?.let { fields += DraftField("Dance style", it) }
        r.danceClass?.let { fields += DraftField("Class", it.displayName) }
        r.alternativeTiming?.takeIf { it.isNotBlank() }?.let { fields += DraftField("Timing", it) }
        r.startingPosition?.takeIf { it.isNotBlank() }?.let { fields += DraftField("Starts", it) }
        r.endingPosition?.takeIf { it.isNotBlank() }?.let { fields += DraftField("Ends", it) }
        plain(r.notes)?.let { fields += DraftField("Notes", it) }
        return Triple(r.name, fields, emptyList())
    }

    private fun plain(html: String?): String? = richTextService.toPlainText(html)?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> lookup(block: () -> T): T? = try { block() } catch (e: RuntimeException) { null }
}
```

- [ ] **Step 4: Run the views test**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftViewsTest"`
Expected: PASS (6 tests). (`DanceClass.displayName` exists; the Class assertion only checks it contains `D`.)

- [ ] **Step 5: Write the failing service tests (mocked collaborators)**

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceImplTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.BulkEditResult
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Figure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import com.jankowski.rafal.dancebook.model.TrainingEvent
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.InOrder
import org.mockito.Mockito
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class AssistantDraftServiceImplTest {

    private val codec = AssistantDraftCodec(DraftTestSupport.mapper())

    private lateinit var store: AssistantDraftStore
    private lateinit var views: AssistantDraftViews
    private lateinit var materialService: MaterialService
    private lateinit var trainingEventService: TrainingEventService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var activeCalendarService: ActiveCalendarService
    private lateinit var service: AssistantDraftServiceImpl

    private val sessionId = UUID.randomUUID()
    private val figureId = UUID.randomUUID()
    private val createdNote = Material().apply { id = UUID.randomUUID() }
    private val card = DraftView(UUID.randomUUID(), DraftKind.NOTE, DraftStatus.SAVED, "t", emptyList(), emptyList(), null, null)

    @BeforeEach
    fun setUp() {
        store = mock(AssistantDraftStore::class.java)
        views = mock(AssistantDraftViews::class.java)
        materialService = mock(MaterialService::class.java)
        trainingEventService = mock(TrainingEventService::class.java)
        danceFigureService = mock(DanceFigureService::class.java)
        activeCalendarService = mock(ActiveCalendarService::class.java)
        service = AssistantDraftServiceImpl(
            store, views, codec, materialService, trainingEventService, danceFigureService, activeCalendarService,
            TransactionTemplate(mock(PlatformTransactionManager::class.java))
        )
    }

    private fun claimed(kind: DraftKind, request: Any): AssistantDraft {
        val draft = AssistantDraft().apply { id = UUID.randomUUID(); this.kind = kind; payload = codec.toPayload(request); status = DraftStatus.SAVED }
        `when`(store.claim(draft.id!!)).thenReturn(draft)
        `when`(store.findOwned(draft.id!!)).thenReturn(draft)
        `when`(views.build(draft)).thenReturn(card)
        return draft
    }

    private fun noteRequest(session: UUID? = sessionId, attended: Boolean = true, figures: List<UUID> = listOf(figureId)) =
        MaterialRequest(name = "Tuesday class", version = 0, trainingEventId = session, figureIds = figures, markAttended = attended)

    /** Whether [method] was called on [mock] with any arguments (Mockito's `any()` does not work with non-null Kotlin parameters). */
    private fun called(mock: Any, method: String) = Mockito.mockingDetails(mock).invocations.any { it.method.name == method }

    // ── notes ──────────────────────────────────────────────────────────────

    @Test
    fun `saving a note calls the form's services in order: create, pin, attendance, then the session link`() {
        val request = noteRequest()
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        `when`(trainingEventService.findById(sessionId)).thenReturn(TrainingEvent().apply { id = sessionId; materialsUrl = "https://example.com/h" })
        `when`(trainingEventService.bulkUpdateMaterial(listOf(sessionId), createdNote.id, "https://example.com/h"))
            .thenReturn(BulkEditResult(updatedCount = 1))

        service.save(draft.id!!)

        val order: InOrder = inOrder(store, materialService, trainingEventService)
        order.verify(store).claim(draft.id!!)
        order.verify(materialService).create(request)
        order.verify(materialService).addFigure(createdNote.id!!, FigureRequest(danceFigureId = figureId))
        order.verify(trainingEventService).updateAttendance(sessionId, AttendanceStatus.ATTENDED)
        order.verify(trainingEventService).bulkUpdateMaterial(listOf(sessionId), createdNote.id, "https://example.com/h")
        order.verify(store).finish(draft.id!!, createdNote.id!!, null)
    }

    @Test
    fun `a note without markAttended leaves attendance alone`() {
        val request = noteRequest(attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        `when`(trainingEventService.findById(sessionId)).thenReturn(TrainingEvent().apply { id = sessionId })
        `when`(trainingEventService.bulkUpdateMaterial(listOf(sessionId), createdNote.id, null)).thenReturn(BulkEditResult(updatedCount = 1))

        service.save(draft.id!!)

        verify(trainingEventService, never()).updateAttendance(sessionId, AttendanceStatus.ATTENDED)
        verify(materialService, never()).addFigure(createdNote.id!!, FigureRequest(danceFigureId = figureId))
    }

    @Test
    fun `a note with no session is saved and nothing is linked`() {
        val request = noteRequest(session = null, attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, createdNote.id!!, null)
        verify(trainingEventService, never()).findById(sessionId)
    }

    @Test
    fun `a link that fails keeps the saved note and says so on the card, like the form does`() {
        val request = noteRequest(attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        `when`(trainingEventService.findById(sessionId)).thenReturn(TrainingEvent().apply { id = sessionId })
        `when`(trainingEventService.bulkUpdateMaterial(listOf(sessionId), createdNote.id, null))
            .thenReturn(BulkEditResult(updatedCount = 0, failedCount = 1))

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, createdNote.id!!, AssistantDraftServiceImpl.LINK_FAILED)
        assertFalse(called(store, "release"), "the note exists, so the draft must not go back to pending")
    }

    @Test
    fun `a failure before the note exists releases the draft with the error`() {
        val request = noteRequest(attended = false, figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenThrow(IllegalArgumentException("Name is too short"))

        service.save(draft.id!!)

        verify(store).release(draft.id!!, "Name is too short")
        assertFalse(called(store, "finish"), "a failed save must not record a created id")
    }

    @Test
    fun `an attendance the user may not record releases the draft, and the note is not linked`() {
        val request = noteRequest(figures = emptyList())
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenReturn(createdNote)
        doThrow(org.springframework.security.access.AccessDeniedException("Only calendar members can record attendance."))
            .`when`(trainingEventService).updateAttendance(sessionId, AttendanceStatus.ATTENDED)

        service.save(draft.id!!)

        verify(store).release(draft.id!!, "Only calendar members can record attendance.")
        verify(trainingEventService, never()).bulkUpdateMaterial(listOf(sessionId), createdNote.id, null)
    }

    @Test
    fun `an unexpected failure shows a generic message, never the exception text`() {
        val request = noteRequest(attended = false, figures = emptyList(), session = null)
        val draft = claimed(DraftKind.NOTE, request)
        `when`(materialService.create(request)).thenThrow(IllegalStateException("could not execute statement; SQL [insert into material ...]"))

        service.save(draft.id!!)

        verify(store).release(draft.id!!, AssistantDraftServiceImpl.GENERIC_FAILURE)
    }

    // ── sessions and figures ───────────────────────────────────────────────

    @Test
    fun `saving a session validates the calendar like the form and creates it there`() {
        val calendarId = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0),
            calendarId = calendarId
        )
        val draft = claimed(DraftKind.TRAINING_EVENT, request)
        val target = TrainingCalendar().apply { id = calendarId }
        `when`(activeCalendarService.validateCreationTarget(calendarId)).thenReturn(target)
        val created = TrainingEvent().apply { id = UUID.randomUUID() }
        `when`(trainingEventService.create(request.copy(calendarId = calendarId))).thenReturn(created)

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, created.id!!, null)
    }

    @Test
    fun `a calendar sync failure on Save leaves the draft pending with the error`() {
        val calendarId = UUID.randomUUID()
        val request = TrainingEventRequest(
            title = "Practice", date = LocalDate.of(2026, 10, 3), startTime = LocalTime.of(10, 0), endTime = LocalTime.of(12, 0),
            calendarId = calendarId
        )
        val draft = claimed(DraftKind.TRAINING_EVENT, request)
        `when`(activeCalendarService.validateCreationTarget(calendarId)).thenReturn(TrainingCalendar().apply { id = calendarId })
        `when`(trainingEventService.create(request.copy(calendarId = calendarId))).thenThrow(CalendarSyncException("Google Calendar is unavailable"))

        service.save(draft.id!!)

        verify(store).release(draft.id!!, "Google Calendar is unavailable")
        assertFalse(called(store, "finish"))
    }

    @Test
    fun `saving a figure calls the figure service`() {
        val request = DanceFigureRequest(name = "Heel Turn", danceTypeId = UUID.randomUUID())
        val draft = claimed(DraftKind.FIGURE, request)
        val created = DanceFigure().apply { id = UUID.randomUUID() }
        `when`(danceFigureService.create(request)).thenReturn(created)

        service.save(draft.id!!)

        verify(store).finish(draft.id!!, created.id!!, null)
    }

    // ── refusals ───────────────────────────────────────────────────────────

    @Test
    fun `a draft that is not pending writes nothing, and the refusal reaches the caller`() {
        val id = UUID.randomUUID()
        `when`(store.claim(id)).thenThrow(DraftNotPendingException(DraftStatus.SAVED))

        assertThrows(DraftNotPendingException::class.java) { service.save(id) }

        assertFalse(called(materialService, "create"))
        assertFalse(called(trainingEventService, "create"))
        assertFalse(called(danceFigureService, "create"))
    }

    @Test
    fun `someone else's draft is a 404 before anything is written`() {
        val id = UUID.randomUUID()
        `when`(store.claim(id)).thenThrow(EntityNotFoundException("Draft not found"))

        assertThrows(EntityNotFoundException::class.java) { service.save(id) }
    }

    // ── edit in form ───────────────────────────────────────────────────────

    @Test
    fun `edit in form discards the draft and points at the matching create form`() {
        val note = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.NOTE }
        val event = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.TRAINING_EVENT }
        val figure = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.FIGURE }
        listOf(note, event, figure).forEach { `when`(store.discardForForm(it.id!!)).thenReturn(it) }

        assertEquals("/materials/new?fromDraft=${note.id}", service.openInForm(note.id!!))
        assertEquals("/training-events/new?fromDraft=${event.id}", service.openInForm(event.id!!))
        assertEquals("/dance-figures/new?fromDraft=${figure.id}", service.openInForm(figure.id!!))
    }

    @Test
    fun `the forms read their draft as the request they bind, or null`() {
        val request = noteRequest()
        val draft = AssistantDraft().apply { id = UUID.randomUUID(); kind = DraftKind.NOTE; payload = codec.toPayload(request) }
        `when`(store.findOpenOrNull(draft.id!!, DraftKind.NOTE)).thenReturn(draft)

        assertEquals(request, service.noteForForm(draft.id!!))
        assertNull(service.noteForForm(UUID.randomUUID()))
        assertNull(service.sessionForForm(draft.id!!))
        assertTrue(service.figureForForm(draft.id!!) == null)
    }
}
```

- [ ] **Step 6: Run it to see it fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftServiceImplTest"`
Expected: compile FAIL (`AssistantDraftServiceImpl` does not exist).

- [ ] **Step 7: Implement the service**

`src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceImpl.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.config.ConditionalOnAssistant
import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.DraftView
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.TrainingEventRequest
import com.jankowski.rafal.dancebook.model.AssistantDraft
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DraftKind
import jakarta.persistence.EntityNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Save is **claim, execute, finish or release**, not one transaction. The training service writes
 * to Google Calendar, and in this codebase that I/O stays out of database transactions. So:
 *
 *  1. [AssistantDraftStore.claim] locks the draft row and moves it to `SAVED` in a short
 *     transaction of its own. A second Save, or a second tab, is refused here.
 *  2. The same service methods the form calls run. For a note the database writes (create, pins,
 *     attendance) share one transaction so they succeed or fail together; linking the session
 *     comes after, because it writes to Google, and, as on the form, its failure keeps the note.
 *  3. [AssistantDraftStore.finish] records what was created; or, if step 2 threw,
 *     [AssistantDraftStore.release] puts the draft back to `PENDING` with the error.
 *
 * A crash between 1 and 3 leaves a draft that reads as saved with no link. That is rare and
 * visible, and it never double-creates, which is the direction to err in.
 */
@Service
@ConditionalOnAssistant
class AssistantDraftServiceImpl(
    private val store: AssistantDraftStore,
    private val views: AssistantDraftViews,
    private val codec: AssistantDraftCodec,
    private val materialService: MaterialService,
    private val trainingEventService: TrainingEventService,
    private val danceFigureService: DanceFigureService,
    private val activeCalendarService: ActiveCalendarService,
    private val transactions: TransactionTemplate
) : AssistantDraftService {

    companion object {
        const val LINK_FAILED = "Saved, but the note could not be linked to the session. Open the session to attach it."
        const val GENERIC_FAILURE = "Could not save this draft. Try again, or use Edit in form."
    }

    private val log = LoggerFactory.getLogger(AssistantDraftServiceImpl::class.java)

    private data class Saved(val entityId: UUID, val notice: String?)

    override fun create(conversationId: UUID, kind: DraftKind, payload: MutableMap<String, Any?>): UUID =
        store.create(conversationId, kind, payload).id!!

    override fun attach(draftId: UUID, messageId: UUID) = store.attach(draftId, messageId)

    override fun view(id: UUID): DraftView = views.build(store.findOwned(id))

    override fun views(ids: Collection<UUID>): List<DraftView> = ids.mapNotNull {
        try { view(it) } catch (e: EntityNotFoundException) { null }
    }

    override fun save(id: UUID): DraftView {
        val draft = store.claim(id)
        try {
            val saved = when (draft.kind) {
                DraftKind.NOTE -> saveNote(draft)
                DraftKind.TRAINING_EVENT -> saveSession(draft)
                DraftKind.FIGURE -> saveFigure(draft)
            }
            store.finish(id, saved.entityId, saved.notice)
        } catch (e: RuntimeException) {
            store.release(id, problemText(e))
        }
        return view(id)
    }

    override fun openInForm(id: UUID): String {
        val draft = store.discardForForm(id)
        val path = when (draft.kind) {
            DraftKind.NOTE -> "/materials/new"
            DraftKind.TRAINING_EVENT -> "/training-events/new"
            DraftKind.FIGURE -> "/dance-figures/new"
        }
        return "$path?fromDraft=$id"
    }

    override fun noteForForm(id: UUID): MaterialRequest? = openDraft(id, DraftKind.NOTE, MaterialRequest::class.java)
    override fun sessionForForm(id: UUID): TrainingEventRequest? = openDraft(id, DraftKind.TRAINING_EVENT, TrainingEventRequest::class.java)
    override fun figureForForm(id: UUID): DanceFigureRequest? = openDraft(id, DraftKind.FIGURE, DanceFigureRequest::class.java)

    private fun <T> openDraft(id: UUID, kind: DraftKind, type: Class<T>): T? =
        store.findOpenOrNull(id, kind)?.let { codec.read(it.payload, type) }

    // ── the three saves ────────────────────────────────────────────────────

    private fun saveNote(draft: AssistantDraft): Saved {
        val request = codec.read(draft.payload, MaterialRequest::class.java)
        val note = transactions.execute {
            val created = materialService.create(request)
            request.figureIds.distinct().forEach { materialService.addFigure(created.id!!, FigureRequest(danceFigureId = it)) }
            val sessionId = request.trainingEventId
            if (request.markAttended && sessionId != null) {
                trainingEventService.updateAttendance(sessionId, AttendanceStatus.ATTENDED)
            }
            created
        }!!
        val notice = request.trainingEventId?.let { linkToSession(it, note.id!!) }
        return Saved(note.id!!, notice)
    }

    /** The same call the form makes. It writes to Google, so it runs after the database transaction. */
    private fun linkToSession(sessionId: UUID, materialId: UUID): String? = try {
        val session = trainingEventService.findById(sessionId)
        val result = trainingEventService.bulkUpdateMaterial(
            sessionIds = listOf(sessionId), materialId = materialId, materialsUrl = session.materialsUrl
        )
        if (result.updatedCount == 0) LINK_FAILED else null
    } catch (e: RuntimeException) {
        log.warn("Note {} was saved but could not be linked to session {}", materialId, sessionId, e)
        LINK_FAILED
    }

    private fun saveSession(draft: AssistantDraft): Saved {
        val request = codec.read(draft.payload, TrainingEventRequest::class.java)
        val target = activeCalendarService.validateCreationTarget(request.calendarId)
        val created = trainingEventService.create(request.copy(calendarId = target.id))
        return Saved(created.id!!, null)
    }

    private fun saveFigure(draft: AssistantDraft): Saved {
        val created = danceFigureService.create(codec.read(draft.payload, DanceFigureRequest::class.java))
        return Saved(created.id!!, null)
    }

    /** What a person may read of a failure. Anything unexpected is logged, and the card says something generic. */
    private fun problemText(e: RuntimeException): String = when (e) {
        is CalendarSyncException, is IllegalArgumentException, is EntityNotFoundException, is AccessDeniedException ->
            e.message?.takeIf { it.isNotBlank() } ?: GENERIC_FAILURE
        else -> {
            log.error("Saving a draft failed", e)
            GENERIC_FAILURE
        }
    }
}
```

- [ ] **Step 8: Run the service tests**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftServiceImplTest"`
Expected: PASS (14 tests). If `verify(store).finish(...)` fails on a `null` notice match, check the `Saved.notice` for that path is exactly `null`.

- [ ] **Step 9: Write the integration test** (needs Docker). It drives the real services and database through the headline acceptance criteria.

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceIntegrationTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import com.jankowski.rafal.dancebook.repository.MaterialRepository
import com.jankowski.rafal.dancebook.repository.TrainingEventRepository
import com.jankowski.rafal.dancebook.repository.TrainingRecordRepository
import jakarta.persistence.EntityNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime
import java.util.UUID

/** Save through the real services and database: the acceptance criteria that a mock cannot prove. */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class AssistantDraftServiceIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var service: AssistantDraftService
    @Autowired private lateinit var conversationService: AssistantConversationService
    @Autowired private lateinit var codec: AssistantDraftCodec
    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository
    @Autowired private lateinit var materials: MaterialRepository
    @Autowired private lateinit var trainingEvents: TrainingEventRepository
    @Autowired private lateinit var trainingRecords: TrainingRecordRepository
    @Autowired private lateinit var danceFigures: DanceFigureRepository
    @Autowired private lateinit var danceTypes: DanceTypeRepository
    @Autowired private lateinit var danceCategories: DanceCategoryRepository
    @Autowired private lateinit var transactions: TransactionTemplate

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var danceType: DanceType
    private lateinit var catalogFigure: DanceFigure
    private lateinit var session: TrainingEvent

    private fun newUser(name: String) = appUsers.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        drafts.deleteAll()
        messages.deleteAll()
        conversations.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
        `when`(appUserService.getCurrentUser()).thenReturn(alice)
        val category = danceCategories.save(DanceCategory().apply { name = "Draft Cat " + UUID.randomUUID() })
        danceType = danceTypes.save(DanceType().apply { name = "Draft Style " + UUID.randomUUID(); this.category = category })
        catalogFigure = danceFigures.save(DanceFigure().apply {
            name = "Feather Step"; this.danceType = danceType; alternativeTiming = "S Q Q"
        })
        session = trainingEvents.save(TrainingEvent().apply {
            title = "Standard group class"
            startTime = LocalDateTime.now().minusDays(2)
            endTime = LocalDateTime.now().minusDays(2).plusHours(1)
            createdBy = alice
        })
    }

    private fun draftOf(kind: DraftKind, request: Any): UUID {
        val conversation = conversationService.start("wrap up")
        return service.create(conversation.id!!, kind, codec.toPayload(request))
    }

    private fun wrapUpNote(title: String = "Tuesday class") = MaterialRequest(
        name = title, description = "<div>Head drops.</div>", version = 0,
        trainingEventId = session.id, figureIds = listOf(catalogFigure.id!!), markAttended = true
    )

    @Test
    fun `saving a wrap-up note creates the note, pins the figure, writes the attendance record and links the session`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote())

        val card = service.save(id)

        assertEquals(DraftStatus.SAVED, card.status)
        assertNull(card.notice)
        val noteId = UUID.fromString(card.savedUrl!!.removePrefix("/materials/"))
        transactions.executeWithoutResult {
            val note = materials.findById(noteId).get()
            assertEquals("Tuesday class", note.name)
            assertEquals(listOf(catalogFigure.id), note.figures.mapNotNull { it.danceFigure?.id })
            val saved = trainingEvents.findById(session.id!!).get()
            assertEquals(noteId, saved.material?.id, "the session is linked to the note")
            assertEquals(AttendanceStatus.ATTENDED, saved.attendanceFor(alice))
        }
        assertNotNull(trainingRecords.findByTrainingEventIdAndCreatedBy(session.id!!, alice), "the TrainingRecord is written")
    }

    @Test
    fun `a second Save of the same draft is refused and creates nothing more`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote("Only once"))
        service.save(id)

        assertThrows(DraftNotPendingException::class.java) { service.save(id) }

        assertEquals(1, materials.findAll().count { it.name == "Only once" })
    }

    @Test
    fun `user B cannot save, open in the form or even see user A's draft`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote("Private to Alice"))
        `when`(appUserService.getCurrentUser()).thenReturn(bob)

        assertThrows(EntityNotFoundException::class.java) { service.save(id) }
        assertThrows(EntityNotFoundException::class.java) { service.openInForm(id) }
        assertThrows(EntityNotFoundException::class.java) { service.view(id) }
        assertNull(service.noteForForm(id))
        assertEquals(0, materials.findAll().count { it.name == "Private to Alice" })
        assertEquals(DraftStatus.PENDING, drafts.findById(id).get().status)
    }

    @Test
    fun `a Save that fails leaves the draft pending with the error on the card, and nothing is created`() {
        val unknownType = UUID.randomUUID()
        val id = draftOf(DraftKind.FIGURE, DanceFigureRequest(name = "Ghost Figure", danceTypeId = unknownType))

        val card = service.save(id)

        assertEquals(DraftStatus.PENDING, card.status)
        assertNotNull(card.notice)
        assertNull(card.savedUrl)
        assertTrue(danceFigures.findAll().none { it.name == "Ghost Figure" })
    }

    @Test
    fun `a failed Save can be retried and a later Save goes through`() {
        val request = DanceFigureRequest(name = "Retry Figure", danceTypeId = UUID.randomUUID())
        val id = draftOf(DraftKind.FIGURE, request)
        assertEquals(DraftStatus.PENDING, service.save(id).status)

        // The user fixes the problem (here: the style now exists) by way of the stored payload.
        val fixed = drafts.findById(id).get().apply { payload = codec.toPayload(request.copy(danceTypeId = danceType.id)) }
        drafts.save(fixed)

        val card = service.save(id)

        assertEquals(DraftStatus.SAVED, card.status)
        assertTrue(danceFigures.findAll().any { it.name == "Retry Figure" })
    }

    @Test
    fun `opening a draft in the form discards it, so its card can no longer save`() {
        val id = draftOf(DraftKind.NOTE, wrapUpNote("Opened in the form"))

        assertEquals("/materials/new?fromDraft=$id", service.openInForm(id))

        assertEquals(DraftStatus.DISCARDED, service.view(id).status)
        assertThrows(DraftNotPendingException::class.java) { service.save(id) }
        assertNotNull(service.noteForForm(id), "the form can still read what was discarded")
    }
}
```

- [ ] **Step 10: Run the integration test**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftServiceIntegrationTest"`
Expected: PASS (6 tests). If the wrap-up test fails on attendance or the link, read the stack: `TrainingEventService.updateAttendance` needs `createdBy == alice` for a session with no calendar (it is), and `bulkUpdateMaterial` makes no Google call because the session's `googleEventId` is null. If `TrainingEvent` needs another non-null column, set it in the fixture.

- [ ] **Step 11: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftViews.kt src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceImpl.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftViewsTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceImplTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftServiceIntegrationTest.kt
git commit -m "feat: save a draft through the form's services, once (#149)"
```

---

### Task 6: Wire drafts into the assistant loop

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDtos.kt` (`AssistantMessageView.drafts`)
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantServiceImpl.kt`
- Modify: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantServiceTest.kt` (constructor)
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftLoopTest.kt`

**Interfaces:**
- Consumes: Task 3's `AssistantTurnScope`/`ToolTurn`, Task 4's `AssistantDraftTools`/`AssistantDraftService`.
- Produces: `AssistantMessageView(role, text, cards, error, drafts: List<DraftView> = emptyList())`; `AssistantServiceImpl` constructors gain `draftTools: AssistantDraftTools`, `drafts: AssistantDraftService`, `turnScope: AssistantTurnScope` (after `readTools`).

- [ ] **Step 1: Write the failing loop test**

`src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftLoopTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.service

import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.dto.PageContext
import com.jankowski.rafal.dancebook.dto.PageContextType
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AssistantRole
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.TrainingCalendar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.model.tool.ToolCallingManager
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The whole loop with a scripted model that drafts: the tools, the grounding and the stored messages together. */
class AssistantDraftLoopTest {

    private val user = AppUser().apply { id = UUID.randomUUID(); displayName = "Rafał" }
    private val mapper = DraftTestSupport.mapper()
    private val clock = Clock.fixed(LocalDateTime.of(2026, 9, 30, 10, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val figure = DanceFigure().apply {
        id = UUID.randomUUID(); name = "Feather Step"; danceType = DanceType().apply { name = "Foxtrot" }; alternativeTiming = "S Q Q"
    }

    private lateinit var conversations: FakeAssistantConversationService
    private lateinit var drafts: FakeAssistantDraftService
    private lateinit var danceFigureService: DanceFigureService
    private lateinit var materialService: MaterialService
    private lateinit var trainingEventService: TrainingEventService
    private val turns = AssistantTurnScope()

    @BeforeEach
    fun setUp() {
        conversations = FakeAssistantConversationService(user)
        drafts = FakeAssistantDraftService()
        danceFigureService = mock(DanceFigureService::class.java)
        `when`(danceFigureService.findAll(null, null, null, "feather", null, null)).thenReturn(listOf(figure))
        materialService = mock(MaterialService::class.java)
        trainingEventService = mock(TrainingEventService::class.java)
    }

    private fun service(model: ScriptedChatModel): AssistantServiceImpl {
        val appUserService = mock(AppUserService::class.java)
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        val danceTypeService = mock(DanceTypeService::class.java)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        val danceCategoryService = mock(DanceCategoryService::class.java)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }))
        val calendars = mock(ActiveCalendarService::class.java)
        `when`(calendars.creationTarget()).thenReturn(TrainingCalendar().apply { id = UUID.randomUUID() })
        val readTools = AssistantReadTools(
            materialService, danceFigureService, danceTypeService, trainingEventService, appUserService, RichTextServiceImpl(), clock
        )
        val draftTools = AssistantDraftTools(
            turns, AssistantGrounding(conversations, mapper), drafts, AssistantDraftCodec(mapper),
            DraftTestSupport.validator(), danceTypeService, danceCategoryService, calendars
        )
        val pageContexts = AssistantPageContextResolver(
            materialService, danceFigureService, trainingEventService, mock(ChoreographyService::class.java)
        )
        return AssistantServiceImpl(
            AssistantModelGateway(model), ToolCallingManager.builder().build(), readTools, draftTools, drafts, turns,
            conversations, pageContexts, appUserService, AssistantRateLimiter(clock), mapper, clock, Duration.ofSeconds(5)
        )
    }

    private val home = PageContext(PageContextType.HOME)

    @Test
    fun `search then draft: the figure found by the search is pinned, and the draft comes back as a card`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("search_figures", """{"query":"feather"}""", id = "c1"),
            ScriptedChatModel.toolCall(
                "draft_note",
                """{"title":"Tuesday class","text":"Head drops.","figureIds":["${figure.id}"]}""", id = "c2"
            ),
            ScriptedChatModel.text("I drafted the note. Check the card, then Save.")
        ))

        val turn = service(model).send(null, "Tuesday's class: feather step, head dropping", home)

        val draft = drafts.created.single()
        val request = mapper.convertValue(draft.payload, MaterialRequest::class.java)
        assertEquals(listOf(figure.id), request.figureIds)
        val reply = turn.messages.last()
        assertEquals("I drafted the note. Check the card, then Save.", reply.text)
        assertEquals(listOf(draft.id), reply.drafts.map { it.id })
        assertEquals("Tuesday class", reply.drafts.single().heading)
        assertEquals(1, reply.cards.size, "the search result card is still shown")
    }

    @Test
    fun `a draft tool never writes: no domain service is asked to create anything`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("search_figures", """{"query":"feather"}""", id = "c1"),
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class","figureIds":["${figure.id}"]}""", id = "c2"),
            ScriptedChatModel.text("Drafted.")
        ))

        service(model).send(null, "note it", home)

        // Whatever the arguments, no write method of any domain service was called.
        val writes = setOf("create", "update", "delete", "addFigure", "updateAttendance", "bulkUpdateMaterial", "removeFigure")
        listOf<Any>(materialService, danceFigureService, trainingEventService).forEach { service ->
            val called = Mockito.mockingDetails(service).invocations.map { it.method.name }.filter { it in writes }
            assertTrue(called.isEmpty(), "a draft tool wrote through $service: $called")
        }
        assertEquals(1, drafts.created.size)
    }

    @Test
    fun `an id the model invents is dropped from the stored draft`() {
        val invented = UUID.randomUUID()
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class","figureIds":["$invented"]}""", id = "c1"),
            ScriptedChatModel.text("Drafted.")
        ))

        service(model).send(null, "note it", home)

        assertTrue(mapper.convertValue(drafts.created.single().payload, MaterialRequest::class.java).figureIds.isEmpty())
    }

    @Test
    fun `the draft is tied to the tool message that produced it`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_figure", """{"name":"Heel Turn","danceStyle":"Waltz"}""", id = "c1"),
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class"}""", id = "c2"),
            ScriptedChatModel.text("Drafted.")
        ))

        service(model).send(null, "note it", home)

        val toolMessages = conversations.stored.filter { it.role == AssistantRole.TOOL }
        val noteDraft = drafts.created.single()
        assertEquals(toolMessages.last().id, drafts.attached[noteDraft.id])
    }

    @Test
    fun `a validation failure is stored as an ordinary tool message, and the model's retry produces the draft`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_note", """{"title":"x"}""", id = "c1"),
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class"}""", id = "c2"),
            ScriptedChatModel.text("Drafted.")
        ))

        val turn = service(model).send(null, "note it", home)

        assertEquals(1, drafts.created.size)
        assertEquals(1, turn.messages.last().drafts.size)
    }

    @Test
    fun `the model is offered the draft tools and told that writes are only drafts`() {
        val model = ScriptedChatModel(listOf(ScriptedChatModel.text("ok")))

        service(model).send(null, "hello", home)

        val system = model.prompts.first().instructions.filterIsInstance<SystemMessage>().single().text
        assertTrue(system.contains("draft_note"), system)
        assertTrue(system.contains("Save"), system)
        assertFalse(system.contains("cannot create, change or delete anything yet"), system)
        val options = model.prompts.first().options as org.springframework.ai.google.genai.GoogleGenAiChatOptions
        val names = options.toolCallbacks.map { it.toolDefinition.name() }
        assertTrue(names.containsAll(listOf("search_figures", "draft_note", "draft_training_event", "draft_figure")), names.toString())
    }

    @Test
    fun `reopening a conversation shows its drafts under the reply that made them`() {
        val model = ScriptedChatModel(listOf(
            ScriptedChatModel.toolCall("draft_note", """{"title":"Tuesday class"}""", id = "c1"),
            ScriptedChatModel.text("Drafted.")
        ))
        val svc = service(model)
        val turn = svc.send(null, "note it", home)

        val view = svc.conversation(turn.conversationId!!)

        assertEquals(1, view.messages.last().drafts.size)
        assertEquals(0, view.messages.first().drafts.size)
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftLoopTest"`
Expected: compile FAIL (`AssistantServiceImpl` has no `draftTools`/`drafts`/`turnScope`; `AssistantMessageView` has no `drafts`).

- [ ] **Step 3: Add `drafts` to the message view**

In `src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDtos.kt`, replace:

```kotlin
data class AssistantMessageView(
    val role: AssistantRole,
    val text: String,
    val cards: List<ResultCard> = emptyList(),
    val error: Boolean = false
)
```

with:

```kotlin
data class AssistantMessageView(
    val role: AssistantRole,
    val text: String,
    val cards: List<ResultCard> = emptyList(),
    val error: Boolean = false,
    /** The draft cards this reply's tools produced (#149), in the order they were made. */
    val drafts: List<DraftView> = emptyList()
)
```

- [ ] **Step 4: Change the loop**

In `src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantServiceImpl.kt` make these edits.

Add imports (next to the existing ones):

```kotlin
import com.jankowski.rafal.dancebook.dto.DraftView
```

Replace both constructors' parameter lists so `draftTools`, `drafts` and `turnScope` follow `readTools`. The primary constructor becomes:

```kotlin
class AssistantServiceImpl(
    private val gateway: AssistantModelGateway,
    private val toolCallingManager: ToolCallingManager,
    private val readTools: AssistantReadTools,
    private val draftTools: AssistantDraftTools,
    private val drafts: AssistantDraftService,
    private val turnScope: AssistantTurnScope,
    private val conversations: AssistantConversationService,
    private val pageContexts: AssistantPageContextResolver,
    private val appUserService: AppUserService,
    private val rateLimiter: AssistantRateLimiter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val timeout: Duration
) : AssistantService {

    /** Spring's constructor: the timeout comes from configuration. */
    @org.springframework.beans.factory.annotation.Autowired
    constructor(
        gateway: AssistantModelGateway,
        toolCallingManager: ToolCallingManager,
        readTools: AssistantReadTools,
        draftTools: AssistantDraftTools,
        drafts: AssistantDraftService,
        turnScope: AssistantTurnScope,
        conversations: AssistantConversationService,
        pageContexts: AssistantPageContextResolver,
        appUserService: AppUserService,
        rateLimiter: AssistantRateLimiter,
        objectMapper: ObjectMapper,
        clock: Clock,
        googleAi: GoogleAiProperties
    ) : this(
        gateway, toolCallingManager, readTools, draftTools, drafts, turnScope, conversations, pageContexts,
        appUserService, rateLimiter, objectMapper, clock, Duration.ofSeconds(googleAi.assistantTimeoutSeconds)
    )
```

Replace the body of `answer` so the loop runs inside the turn scope and offers both tool sets:

```kotlin
    private fun answer(conversationId: UUID, userName: String, page: ResolvedPage): AssistantMessageView =
        turnScope.run(ToolTurn(conversationId, page)) { answerInTurn(conversationId, userName, page) }

    private fun answerInTurn(conversationId: UUID, userName: String, page: ResolvedPage): AssistantMessageView {
        val deadline = System.nanoTime() + timeout.toNanos()
        fun remaining(): Duration = Duration.ofNanos(deadline - System.nanoTime())

        val history: List<Message> = conversations.recentContext(conversationId, AssistantService.HISTORY_LIMIT).map {
            if (it.role == AssistantRole.USER) UserMessage(it.content) else AiAssistantMessage(it.content)
        }
        val withTools = GoogleGenAiChatOptions.builder()
            .toolCallbacks(readTools.callbacks + draftTools.callbacks)
            .internalToolExecutionEnabled(false)
            .build()
        var prompt = Prompt(listOf<Message>(SystemMessage(systemPrompt(userName, page))) + history, withTools)

        var text: String? = null
        for (round in 1..AssistantService.MAX_TOOL_ROUNDS) {
            val response = gateway.call(prompt, remaining())
            if (!response.hasToolCalls()) {
                text = response.result?.output?.text
                break
            }
            val calls = response.result.output.toolCalls
            val executed = toolCallingManager.executeToolCalls(prompt, response)
            record(conversationId, calls, executed.conversationHistory().last() as ToolResponseMessage)
            prompt = Prompt(executed.conversationHistory(), withTools)
        }

        if (text == null) {
            // Out of rounds, or the last round still wanted a tool: one more call with no tools.
            val noTools = GoogleGenAiChatOptions.builder().internalToolExecutionEnabled(false).build()
            text = gateway.call(Prompt(prompt.instructions, noTools), remaining()).result?.output?.text
        }

        val finalText = text?.trim()?.takeIf { it.isNotEmpty() } ?: AssistantService.GAVE_UP_TEXT
        conversations.append(conversationId, AssistantRole.ASSISTANT, finalText)
        val since = conversations.messages(conversationId).takeLastWhile { it.role != AssistantRole.USER }.filter { it.role == AssistantRole.TOOL }
        return AssistantMessageView(
            AssistantRole.ASSISTANT, finalText, since.flatMap { cardsOf(it) }, drafts = drafts.views(since.mapNotNull { draftIdOf(it) })
        )
    }
```

Replace `record` so a stored draft tool message is tied to its draft:

```kotlin
    private fun record(conversationId: UUID, calls: List<AiAssistantMessage.ToolCall>, results: ToolResponseMessage) {
        for (response in results.responses) {
            val arguments = calls.firstOrNull { it.id == response.id }?.arguments
            @Suppress("UNCHECKED_CAST")
            val result = try {
                objectMapper.readValue(response.responseData, Map::class.java) as MutableMap<String, Any?>
            } catch (e: Exception) {
                mutableMapOf<String, Any?>("total" to 0, "items" to emptyList<Any>(), "message" to "Unreadable tool result")
            }
            val stored = conversations.append(
                conversationId, AssistantRole.TOOL, response.name,
                mutableMapOf("name" to response.name, "arguments" to arguments, "result" to result)
            )
            // The draft was made before its tool message existed; now it can point at it.
            draftIdOf(stored)?.let { drafts.attach(it, stored.id!!) }
        }
    }
```

Delete the now-unused `cardsSince` function, and replace `toViews` and add `draftIdOf`:

```kotlin
    private fun draftIdOf(message: AssistantMessage): UUID? {
        val id = (message.toolPayload?.get("result") as? Map<*, *>)?.get("draftId") as? String ?: return null
        return try { UUID.fromString(id) } catch (e: IllegalArgumentException) { null }
    }

    private fun toViews(messages: List<AssistantMessage>): List<AssistantMessageView> {
        val views = mutableListOf<AssistantMessageView>()
        var pending = mutableListOf<ResultCard>()
        var pendingDrafts = mutableListOf<UUID>()
        for (m in messages) {
            when (m.role) {
                AssistantRole.USER -> {
                    pending = mutableListOf(); pendingDrafts = mutableListOf()
                    views += AssistantMessageView(m.role, m.content)
                }
                AssistantRole.TOOL -> { pending += cardsOf(m); draftIdOf(m)?.let { pendingDrafts += it } }
                AssistantRole.ASSISTANT -> {
                    views += AssistantMessageView(m.role, m.content, pending.toList(), drafts = drafts.views(pendingDrafts))
                    pending = mutableListOf(); pendingDrafts = mutableListOf()
                }
            }
        }
        return views
    }
```

Replace the `systemPrompt` body's instruction lines. The two lines

```
            Use the tools to search and read the user's notes, the figure catalog and their training sessions. Do not guess: if you need a fact, call a tool. Never invent ids, titles or dates.
            You cannot create, change or delete anything yet. If asked to, say so and suggest doing it in the app.
```

become:

```
            Use the tools to search and read the user's notes, the figure catalog and their training sessions. Do not guess: if you need a fact, call a tool. Never invent ids, titles or dates.
            To create something, call draft_note, draft_training_event or draft_figure. They only prepare a draft: the user sees a card and nothing is saved until they press Save. After drafting, say so in one sentence and do not repeat the fields. You cannot edit or delete existing notes, sessions or figures; if asked, say so and suggest doing it in the app.
            Every id in a draft (figure, note, session) must come from a tool result in this conversation. Search first, in an earlier step, and never send an id you did not get from a tool.
            If a draft tool says no draft was created, fix what it names and call it once more. If it still fails, ask the user for what is missing.
            When the user wraps up a session (for example: Wrap up "Standard group class" (Tuesday 23 Sep): feather step, head dropping), find the session with list_sessions for that date, then call draft_note with its sessionId and markAttended true, pinning any figures you can find with search_figures.
```

- [ ] **Step 5: Fix the existing service test's constructor**

In `src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantServiceTest.kt`, replace the `service` helper:

```kotlin
    private fun service(model: ScriptedChatModel, timeout: Duration = Duration.ofSeconds(5), limiter: AssistantRateLimiter = AssistantRateLimiter(clock)) =
        AssistantServiceImpl(
            AssistantModelGateway(model), ToolCallingManager.builder().build(), tools, conversations,
            pageContexts, appUserService, limiter, jacksonObjectMapper(), clock, timeout
        )
```

with:

```kotlin
    private fun service(model: ScriptedChatModel, timeout: Duration = Duration.ofSeconds(5), limiter: AssistantRateLimiter = AssistantRateLimiter(clock)): AssistantServiceImpl {
        val mapper = DraftTestSupport.mapper()
        val drafts = FakeAssistantDraftService()
        val turns = AssistantTurnScope()
        val danceTypeService = mock(DanceTypeService::class.java)
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        val draftTools = AssistantDraftTools(
            turns, AssistantGrounding(conversations, mapper), drafts, AssistantDraftCodec(mapper), DraftTestSupport.validator(),
            danceTypeService, mock(DanceCategoryService::class.java), mock(ActiveCalendarService::class.java)
        )
        return AssistantServiceImpl(
            AssistantModelGateway(model), ToolCallingManager.builder().build(), tools, draftTools, drafts, turns, conversations,
            pageContexts, appUserService, limiter, mapper, clock, timeout
        )
    }
```

- [ ] **Step 6: Run the loop tests and the existing assistant tests**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.service.AssistantDraftLoopTest" --tests "com.jankowski.rafal.dancebook.service.AssistantServiceTest"`
Expected: PASS. The existing `AssistantServiceTest` cases should pass unchanged apart from the helper. If one of them asserts the old system-prompt sentence, update that assertion to the new wording.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/dto/AssistantDtos.kt src/main/kotlin/com/jankowski/rafal/dancebook/service/AssistantServiceImpl.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantServiceTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/service/AssistantDraftLoopTest.kt
git commit -m "feat: let the assistant loop draft, ground ids and return draft cards (#149)"
```

---

### Task 7: The draft card, Save and Edit in form endpoints

**Files:**
- Modify: `src/main/resources/templates/fragments/assistant.html` (add `assistantDraft`, pass `drafts` through `assistantMessage`)
- Modify: `src/main/resources/templates/assistant/fragments.html` (`draftCard` fragment, `drafts` in `turn` and `conversation`, greeting text)
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantWebController.kt`
- Modify: `src/test/resources/templates/test/catalog-harness.html`
- Modify: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/FragmentCatalogRenderingTest.kt`
- Modify: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantFragmentRenderingTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantDraftWebIntegrationTest.kt`

**Interfaces:**
- Consumes: `DraftView`, `AssistantDraftService.save/view/openInForm`, `DraftNotPendingException`.
- Produces:
  - Catalog fragment `fragments/assistant :: assistantDraft(draft)`, root `id="assistant-draft-<id>"` and `data-assistant-draft`
  - `assistant/fragments :: draftCard` (model attribute `draft: DraftView`)
  - `POST /assistant/drafts/{id}/save` → the card (200 for htmx, 409 for a plain request when already handled)
  - `POST /assistant/drafts/{id}/edit` → `HX-Redirect` header for htmx, a 303 redirect otherwise

- [ ] **Step 1: Write the failing rendering tests**

Add to `src/test/resources/templates/test/catalog-harness.html`, after the `assistantMessageError` block:

```html
<!-- ASSISTANT: assistantDraft -->
<div th:fragment="assistantDraftRequired">
    <div th:replace="~{fragments/assistant :: assistantDraft(draft=${sampleDraft})}"></div>
</div>

<div th:fragment="assistantDraftAll">
    <div th:replace="~{fragments/assistant :: assistantDraft(draft=${sampleDraftFull}, cls='extra-cls')}"></div>
</div>

<div th:fragment="assistantDraftSaved">
    <div th:replace="~{fragments/assistant :: assistantDraft(draft=${sampleDraftSaved})}"></div>
</div>

<div th:fragment="assistantDraftDiscarded">
    <div th:replace="~{fragments/assistant :: assistantDraft(draft=${sampleDraftDiscarded})}"></div>
</div>
```

In `FragmentCatalogRenderingTest.kt`, add the model attributes next to `sampleCards` (inside the harness controller's render method, before `return "test/catalog-harness :: $fragmentName"`):

```kotlin
        val draftId = java.util.UUID.fromString("00000000-0000-0000-0000-00000000d001")
        fun draft(
            status: com.jankowski.rafal.dancebook.model.DraftStatus,
            notice: String? = null,
            full: Boolean = false,
            saved: Boolean = false
        ) = com.jankowski.rafal.dancebook.dto.DraftView(
            id = draftId,
            kind = com.jankowski.rafal.dancebook.model.DraftKind.NOTE,
            status = status,
            heading = "Tuesday class",
            fields = if (full) listOf(
                com.jankowski.rafal.dancebook.dto.DraftField("Text", "Head drops on step two."),
                com.jankowski.rafal.dancebook.dto.DraftField("Session", "Standard group class")
            ) else emptyList(),
            figures = if (full) listOf(com.jankowski.rafal.dancebook.dto.DraftFigureLine("Feather Step", "S Q Q", "/dance-figures/f1")) else emptyList(),
            savedUrl = if (saved) "/materials/m1" else null,
            notice = notice
        )
        model.addAttribute("sampleDraft", draft(com.jankowski.rafal.dancebook.model.DraftStatus.PENDING))
        model.addAttribute("sampleDraftFull", draft(com.jankowski.rafal.dancebook.model.DraftStatus.PENDING, notice = "Name is too short", full = true))
        model.addAttribute("sampleDraftSaved", draft(com.jankowski.rafal.dancebook.model.DraftStatus.SAVED, notice = "Saved, but not linked", full = true, saved = true))
        model.addAttribute("sampleDraftDiscarded", draft(com.jankowski.rafal.dancebook.model.DraftStatus.DISCARDED, full = true))
```

Add the names to the two string lists in the `@ValueSource` (required-only list after `"assistantMessageRequired",`; all-parameters list after `"assistantMessageError",`):

```kotlin
            "assistantDraftRequired",
```

and

```kotlin
            "assistantDraftAll",
            "assistantDraftSaved",
            "assistantDraftDiscarded",
```

Add these tests after `a user bubble is escaped text`:

```kotlin
    @Test
    fun `a pending draft card shows its fields, each figure with its timing, Save and Edit in form`() {
        mockMvc.perform(get("/test/catalog/assistantDraftAll").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistant-draft-00000000-0000-0000-0000-00000000d001\"")))
            .andExpect(content().string(containsString("Tuesday class")))
            .andExpect(content().string(containsString("Head drops on step two.")))
            .andExpect(content().string(containsString("Standard group class")))
            .andExpect(content().string(containsString("Feather Step")))
            .andExpect(content().string(containsString("S Q Q")))
            .andExpect(content().string(containsString("/assistant/drafts/00000000-0000-0000-0000-00000000d001/save")))
            .andExpect(content().string(containsString("/assistant/drafts/00000000-0000-0000-0000-00000000d001/edit")))
            .andExpect(content().string(containsString("Name is too short")))
            .andExpect(content().string(containsString("extra-cls")))
    }

    @Test
    fun `a saved draft card is a link to what was created and has no Save button`() {
        mockMvc.perform(get("/test/catalog/assistantDraftSaved").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("href=\"/materials/m1\"")))
            .andExpect(content().string(containsString("Saved, but not linked")))
            .andExpect(content().string(not(containsString("/save"))))
    }

    @Test
    fun `a discarded draft card is greyed out and has no buttons`() {
        mockMvc.perform(get("/test/catalog/assistantDraftDiscarded").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("opacity-60")))
            .andExpect(content().string(not(containsString("/save"))))
            .andExpect(content().string(not(containsString("/edit"))))
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.FragmentCatalogRenderingTest"`
Expected: FAIL (the `assistantDraft` fragment does not exist; Thymeleaf reports it).

- [ ] **Step 3: Write the card fragment**

In `src/main/resources/templates/fragments/assistant.html`, add this fragment before the closing `</body>`, and change `assistantMessage` to render drafts after cards.

The new fragment:

```html
<!-- A draft the assistant prepared. Parameters: draft (a DraftView); optional cls.
     PENDING: every field, each figure with its timing, Save and Edit in form. SAVED: a link to what was created.
     DISCARDED (opened in the form): greyed out. A notice is an error while PENDING, a caveat once SAVED.
     Nothing here writes anything; the two buttons post to /assistant/drafts/{id}/save and /edit. -->
<article th:fragment="assistantDraft"
         th:id="${'assistant-draft-' + draft.id}"
         data-assistant-draft
         class="card flex flex-col gap-3 p-4"
         th:classappend="${(draft.status.name() == 'DISCARDED' ? 'opacity-60' : '') + (cls != null ? ' ' + cls : '')}">
    <div class="flex items-center gap-2">
        <span th:replace="~{fragments/icon :: icon(name=${draft.kind.name() == 'NOTE' ? 'description' : (draft.kind.name() == 'FIGURE' ? 'accessibility_new' : 'calendar_month')}, size='md', cls='text-primary', title=null)}"></span>
        <h3 class="flex-1 min-w-0 truncate text-[15px] font-semibold m-0" th:text="${draft.heading}">Heading</h3>
        <th:block th:if="${draft.status.name() == 'PENDING'}"><span th:replace="~{fragments/badge :: badge(label='Draft', variant='primary')}"></span></th:block>
        <th:block th:if="${draft.status.name() == 'SAVED'}"><span th:replace="~{fragments/badge :: badge(label='Saved', variant='success')}"></span></th:block>
        <th:block th:if="${draft.status.name() == 'DISCARDED'}"><span th:replace="~{fragments/badge :: badge(label='Opened in the form', variant='secondary')}"></span></th:block>
    </div>

    <dl th:if="${!#lists.isEmpty(draft.fields)}" class="m-0 flex flex-col gap-1.5 text-[13px]">
        <div th:each="f : ${draft.fields}" class="flex gap-2">
            <dt class="w-24 shrink-0 text-on-surface-variant" th:text="${f.label}">Label</dt>
            <dd class="m-0 min-w-0 flex-1 whitespace-pre-wrap" th:text="${f.value}">Value</dd>
        </div>
    </dl>

    <div th:if="${!#lists.isEmpty(draft.figures)}" class="flex flex-col gap-1.5">
        <span class="text-[13px] text-on-surface-variant">Figures</span>
        <ul class="m-0 flex list-none flex-col gap-1 p-0">
            <li th:each="fig : ${draft.figures}" class="flex items-center gap-2 text-[13px]">
                <a th:href="${fig.url}" class="min-w-0 flex-1 truncate text-on-surface hover:underline" th:text="${fig.name}">Figure</a>
                <span th:if="${fig.timing != null}" class="font-semibold text-primary whitespace-nowrap" th:text="${fig.timing}">S Q Q</span>
            </li>
        </ul>
    </div>

    <th:block th:if="${draft.notice != null}">
        <div th:replace="~{fragments/alert :: alert(variant=${draft.status.name() == 'PENDING' ? 'error' : 'warning'}, text=${draft.notice})}"></div>
    </th:block>

    <th:block th:if="${draft.status.name() == 'SAVED' and draft.savedUrl != null}">
        <a th:href="${draft.savedUrl}" class="text-sm font-semibold text-primary hover:underline">Open what was created</a>
    </th:block>

    <div th:if="${draft.status.name() == 'PENDING'}" class="flex flex-wrap gap-2">
        <button type="button" class="btn-primary"
                th:attr="hx-post=@{/assistant/drafts/{id}/save(id=${draft.id})}"
                hx-target="closest [data-assistant-draft]" hx-swap="outerHTML" hx-disabled-elt="this">Save</button>
        <button type="button" class="btn-outline"
                th:attr="hx-post=@{/assistant/drafts/{id}/edit(id=${draft.id})}"
                hx-swap="none" hx-disabled-elt="this">Edit in form</button>
    </div>
</article>
```

The `th:if` sits on an enclosing `<th:block>` around each badge because `th:if` and `th:replace` must not share an element (AGENTS.md; `ThymeleafConditionalIncludeTest`).

In the same file, in `assistantMessage`, after the `cards` loop and before the closing `</div>` of the assistant branch, add:

```html
        <th:block th:if="${drafts != null}" th:each="d : ${drafts}">
            <div th:replace="~{fragments/assistant :: assistantDraft(draft=${d})}"></div>
        </th:block>
```

Update the comment above `assistantMessage` to read "…An assistant bubble may carry cards (ResultCard list) and drafts (DraftView list); error swaps in the alert."

In `src/main/resources/templates/assistant/fragments.html`:

1. In both the `turn` and `conversation` fragments, add `drafts=${m.drafts}` to the `assistantMessage(...)` call: `assistantMessage(role=${m.role.name()}, text=${m.text}, cards=${m.cards}, error=${m.error}, drafts=${m.drafts})`.
2. Add, before the closing `</body>`:

```html
<!-- One draft card, swapped in place by Save. The controller puts `draft` (a DraftView) in the model. -->
<th:block th:fragment="draftCard">
    <div th:replace="~{fragments/assistant :: assistantDraft(draft=${draft})}"></div>
</th:block>
```

3. Replace the greeting sentence `I can search and read them; I can't change anything yet.` with `I can search them, and draft new notes, sessions and figures for you to confirm before anything is saved.`

- [ ] **Step 4: Run the catalog tests**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.FragmentCatalogRenderingTest" --tests "com.jankowski.rafal.dancebook.controller.web.ThymeleafConditionalIncludeTest" --tests "com.jankowski.rafal.dancebook.controller.web.ThymeleafRestrictedExpressionTest"`
Expected: PASS. If `assistantDraftRequired` fails with a literal `null`, an optional field is rendering unguarded; if a guard test names a line, fix that line.

- [ ] **Step 5: Write the failing endpoint tests**

In `AssistantFragmentRenderingTest.kt`, add `@MockBean private lateinit var draftService: com.jankowski.rafal.dancebook.service.AssistantDraftService` next to the other mocks, and these tests at the end of the class (they need imports for `DraftView`, `DraftField`, `DraftKind`, `DraftStatus`, `DraftNotPendingException`, `redirectedUrl`, `header`):

```kotlin
    private val draftId = UUID.randomUUID()

    private fun card(status: com.jankowski.rafal.dancebook.model.DraftStatus, notice: String? = null) =
        com.jankowski.rafal.dancebook.dto.DraftView(
            draftId, com.jankowski.rafal.dancebook.model.DraftKind.NOTE, status, "Tuesday class",
            emptyList(), emptyList(), if (status == com.jankowski.rafal.dancebook.model.DraftStatus.SAVED) "/materials/m1" else null, notice
        )

    @Test
    fun `save swaps in the card as it is after the save`() {
        `when`(draftService.save(draftId)).thenReturn(card(com.jankowski.rafal.dancebook.model.DraftStatus.SAVED))

        mockMvc.perform(post("/assistant/drafts/$draftId/save").with(csrf()).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistant-draft-$draftId\"")))
            .andExpect(content().string(containsString("href=\"/materials/m1\"")))
    }

    @Test
    fun `a failed save still answers 200 with the pending card and its error, because htmx does not swap a 4xx`() {
        `when`(draftService.save(draftId)).thenReturn(card(com.jankowski.rafal.dancebook.model.DraftStatus.PENDING, "Name is too short"))

        mockMvc.perform(post("/assistant/drafts/$draftId/save").with(csrf()).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Name is too short")))
            .andExpect(content().string(containsString("/assistant/drafts/$draftId/save")))
    }

    @Test
    fun `saving an already saved draft shows its saved card to htmx and is a 409 to anything else`() {
        `when`(draftService.save(draftId)).thenThrow(com.jankowski.rafal.dancebook.service.DraftNotPendingException(com.jankowski.rafal.dancebook.model.DraftStatus.SAVED))
        `when`(draftService.view(draftId)).thenReturn(card(com.jankowski.rafal.dancebook.model.DraftStatus.SAVED))

        mockMvc.perform(post("/assistant/drafts/$draftId/save").with(csrf()).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("href=\"/materials/m1\"")))
        mockMvc.perform(post("/assistant/drafts/$draftId/save").with(csrf()))
            .andExpect(status().isConflict)
    }

    @Test
    fun `edit in form sends htmx to the prefilled form and everything else too`() {
        `when`(draftService.openInForm(draftId)).thenReturn("/materials/new?fromDraft=$draftId")

        mockMvc.perform(post("/assistant/drafts/$draftId/edit").with(csrf()).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(header().string("HX-Redirect", "/materials/new?fromDraft=$draftId"))
        mockMvc.perform(post("/assistant/drafts/$draftId/edit").with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/new?fromDraft=$draftId"))
    }

    @Test
    fun `a turn with a draft renders its card in the thread`() {
        val id = UUID.randomUUID()
        `when`(assistantService.send(id, "note it", com.jankowski.rafal.dancebook.dto.PageContext())).thenReturn(
            AssistantTurn(
                id,
                listOf(
                    AssistantMessageView(AssistantRole.USER, "note it"),
                    AssistantMessageView(AssistantRole.ASSISTANT, "Drafted.", drafts = listOf(card(com.jankowski.rafal.dancebook.model.DraftStatus.PENDING)))
                ),
                true
            )
        )

        mockMvc.perform(post("/assistant/messages").with(csrf()).header("HX-Request", "true").param("conversationId", id.toString()).param("text", "note it"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"assistant-draft-$draftId\"")))
            .andExpect(content().string(containsString("Tuesday class")))
    }
```

Add `import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header` and `import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl` to that file.

- [ ] **Step 6: Run them to see them fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.AssistantFragmentRenderingTest"`
Expected: FAIL (the controller has no `/drafts/...` routes and does not inject the service).

- [ ] **Step 7: Add the endpoints**

In `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantWebController.kt`:

Add imports:

```kotlin
import com.jankowski.rafal.dancebook.service.AssistantDraftService
import com.jankowski.rafal.dancebook.service.DraftNotPendingException
import org.springframework.web.bind.annotation.RequestHeader
```

Add the constructor parameter:

```kotlin
class AssistantWebController(
    private val assistantService: AssistantService,
    private val conversationService: AssistantConversationService,
    private val draftService: AssistantDraftService
) {
```

Add, before `private fun historyFragment`:

```kotlin
    /**
     * Save. Answers 200 with the card in every case that has something to show: saved, or
     * still pending with the error in it, because htmx does not swap a 4xx and the user would
     * see nothing. A draft that was already handled is a 409 to anything that is not htmx.
     * Someone else's draft is a 404, as everywhere else.
     */
    @PostMapping("/drafts/{id}/save")
    fun saveDraft(
        @PathVariable id: UUID,
        @RequestHeader("HX-Request", required = false) htmx: Boolean?,
        response: HttpServletResponse,
        model: Model
    ): String {
        val card = try {
            draftService.save(id)
        } catch (e: DraftNotPendingException) {
            if (htmx != true) response.status = HttpStatus.CONFLICT.value()
            draftService.view(id)
        }
        model.addAttribute("draft", card)
        return "assistant/fragments :: draftCard"
    }

    /** Edit in form: the draft is discarded and the browser goes to the create form, prefilled from it. */
    @PostMapping("/drafts/{id}/edit")
    fun editDraft(
        @PathVariable id: UUID,
        @RequestHeader("HX-Request", required = false) htmx: Boolean?
    ): org.springframework.http.ResponseEntity<Void> {
        val url = draftService.openInForm(id)
        val builder = if (htmx == true) {
            org.springframework.http.ResponseEntity.ok().header("HX-Redirect", url)
        } else {
            org.springframework.http.ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", url)
        }
        return builder.build()
    }
```

(An empty `ResponseEntity` is used rather than a view name so htmx gets a body-less 200 carrying `HX-Redirect`; the non-htmx 303 with a `Location` header satisfies the test's `redirectedUrl(...)`.)

- [ ] **Step 8: Run the controller tests**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.AssistantFragmentRenderingTest" --tests "com.jankowski.rafal.dancebook.controller.web.FragmentCatalogRenderingTest"`
Expected: PASS. Also run `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.AssistantDisabledTest"`: every `/assistant/**` route, including the new ones, must still 404 with no key.

- [ ] **Step 9: Write the end-to-end ownership test** (needs Docker)

`src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantDraftWebIntegrationTest.kt`:

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.dto.DanceFigureRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceType
import com.jankowski.rafal.dancebook.model.DraftKind
import com.jankowski.rafal.dancebook.model.DraftStatus
import com.jankowski.rafal.dancebook.model.Role
import com.jankowski.rafal.dancebook.repository.AppUserRepository
import com.jankowski.rafal.dancebook.repository.AssistantConversationRepository
import com.jankowski.rafal.dancebook.repository.AssistantDraftRepository
import com.jankowski.rafal.dancebook.repository.AssistantMessageRepository
import com.jankowski.rafal.dancebook.repository.DanceCategoryRepository
import com.jankowski.rafal.dancebook.repository.DanceFigureRepository
import com.jankowski.rafal.dancebook.repository.DanceTypeRepository
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantConversationService
import com.jankowski.rafal.dancebook.service.AssistantDraftCodec
import com.jankowski.rafal.dancebook.service.AssistantDraftService
import com.jankowski.rafal.dancebook.service.GoogleCalendarClient
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = ["google.calendar.calendar-id=integration-test-calendar", "google.ai.api-key=test-key"])
class AssistantDraftWebIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var drafts: AssistantDraftRepository
    @Autowired private lateinit var conversations: AssistantConversationRepository
    @Autowired private lateinit var messages: AssistantMessageRepository
    @Autowired private lateinit var appUsers: AppUserRepository
    @Autowired private lateinit var danceFigures: DanceFigureRepository
    @Autowired private lateinit var danceTypes: DanceTypeRepository
    @Autowired private lateinit var danceCategories: DanceCategoryRepository
    @Autowired private lateinit var draftService: AssistantDraftService
    @Autowired private lateinit var conversationService: AssistantConversationService
    @Autowired private lateinit var codec: AssistantDraftCodec

    @MockBean private lateinit var calendarClient: GoogleCalendarClient
    @MockBean private lateinit var appUserService: AppUserService

    private lateinit var alice: AppUser
    private lateinit var bob: AppUser
    private lateinit var danceType: DanceType

    private fun newUser(name: String) = appUsers.save(AppUser().apply {
        username = "$name-${UUID.randomUUID()}"; displayName = name; password = "x"; role = Role.USER
    })

    @BeforeEach
    fun setUp() {
        drafts.deleteAll(); messages.deleteAll(); conversations.deleteAll()
        alice = newUser("alice")
        bob = newUser("bob")
        `when`(appUserService.getCurrentUser()).thenReturn(alice)
        val category = danceCategories.save(DanceCategory().apply { name = "Web Cat " + UUID.randomUUID() })
        danceType = danceTypes.save(DanceType().apply { name = "Web Style " + UUID.randomUUID(); this.category = category })
    }

    private fun aliceFigureDraft(name: String): UUID {
        val conversation = conversationService.start("draft")
        return draftService.create(conversation.id!!, DraftKind.FIGURE, codec.toPayload(DanceFigureRequest(name = name, danceTypeId = danceType.id)))
    }

    @Test
    fun `Save over htmx creates the figure once and swaps in the saved card; a second Save shows the same saved card and creates nothing`() {
        val id = aliceFigureDraft("Web Heel Turn")
        val asAlice = user(alice.username).roles("USER")

        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asAlice).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Open what was created")))
        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asAlice).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Open what was created")))
        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asAlice))
            .andExpect(status().isConflict)

        assertEquals(1, danceFigures.findAll().count { it.name == "Web Heel Turn" })
    }

    @Test
    fun `user B gets 404 for Save and Edit in form on user A's draft, and nothing changes`() {
        val id = aliceFigureDraft("Web Private Figure")
        `when`(appUserService.getCurrentUser()).thenReturn(bob)
        val asBob = user(bob.username).roles("USER")

        mockMvc.perform(post("/assistant/drafts/$id/save").with(csrf()).with(asBob)).andExpect(status().isNotFound)
        mockMvc.perform(post("/assistant/drafts/$id/edit").with(csrf()).with(asBob)).andExpect(status().isNotFound)

        assertEquals(DraftStatus.PENDING, drafts.findById(id).get().status)
        assertTrue(danceFigures.findAll().none { it.name == "Web Private Figure" })
    }

    @Test
    fun `Edit in form redirects htmx to the prefilled form and greys the draft out`() {
        val id = aliceFigureDraft("Web Edit Figure")

        mockMvc.perform(post("/assistant/drafts/$id/edit").with(csrf()).with(user(alice.username).roles("USER")).header("HX-Request", "true"))
            .andExpect(status().isOk)
            .andExpect(header().string("HX-Redirect", "/dance-figures/new?fromDraft=$id"))

        assertEquals(DraftStatus.DISCARDED, drafts.findById(id).get().status)
    }
}
```

- [ ] **Step 10: Run it**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.AssistantDraftWebIntegrationTest"`
Expected: PASS (3 tests).

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/templates/fragments/assistant.html src/main/resources/templates/assistant/fragments.html src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantWebController.kt src/test/resources/templates/test/catalog-harness.html src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/FragmentCatalogRenderingTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantFragmentRenderingTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/AssistantDraftWebIntegrationTest.kt
git commit -m "feat: show drafts as cards with Save and Edit in form (#149)"
```

---

### Task 8: Edit in form: the three create forms open prefilled

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/MaterialWebController.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebController.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/DanceFigureWebController.kt`
- Modify: `src/main/resources/templates/materials/form.html`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/MaterialFromDraftWebTest.kt` (new)
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/DanceFigureWebControllerTest.kt` (add two tests)
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebControllerTest.kt` (add one test, update three calls)

**Interfaces:**
- Consumes: `AssistantDraftService.noteForForm/sessionForForm/figureForForm` (nullable, optional bean).
- Produces: `GET /materials/new?fromDraft=<id>`, `GET /training-events/new?fromDraft=<id>`, `GET /dance-figures/new?fromDraft=<id>`; `POST /materials` applies `figureIds` and `markAttended` after creating the note. Model attributes on the note form: `draftFigures: List<DanceFigure>`, `draftSession: TrainingEvent?`.
- The draft service is injected as an **optional field** (`@Autowired(required = false) var assistantDrafts: AssistantDraftService? = null`), the way `NavbarAdvice` reaches `AssistantNavSupport`, so `@WebMvcTest` slices and tests that construct a controller by hand need no change and nothing breaks when the assistant is off.

- [ ] **Step 1: Write the failing note-form tests**

`src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/MaterialFromDraftWebTest.kt` (the same slice as `MaterialFromSessionWebTest`, plus a mocked `AssistantDraftService`):

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import com.jankowski.rafal.dancebook.config.SecurityConfig
import com.jankowski.rafal.dancebook.dto.BulkEditResult
import com.jankowski.rafal.dancebook.dto.FigureRequest
import com.jankowski.rafal.dancebook.dto.MaterialRequest
import com.jankowski.rafal.dancebook.model.AppUser
import com.jankowski.rafal.dancebook.model.AttendanceStatus
import com.jankowski.rafal.dancebook.model.DanceCategory
import com.jankowski.rafal.dancebook.model.DanceFigure
import com.jankowski.rafal.dancebook.model.Material
import com.jankowski.rafal.dancebook.model.TrainingEvent
import com.jankowski.rafal.dancebook.service.ActiveCalendarService
import com.jankowski.rafal.dancebook.service.ActivityEventService
import com.jankowski.rafal.dancebook.service.AppUserService
import com.jankowski.rafal.dancebook.service.AssistantDraftService
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
import jakarta.persistence.EntityNotFoundException
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.support.RequestDataValueProcessor
import java.time.LocalDateTime
import java.util.UUID

/** "Edit in form" for a note the assistant drafted (#149): the form opens prefilled, and saving it applies the drafted pins and attendance. */
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
@Import(MaterialFromDraftWebTest.CsrfProcessorConfig::class, RichTextServiceImpl::class)
class MaterialFromDraftWebTest {

    @TestConfiguration
    class CsrfProcessorConfig {
        @Bean
        fun requestDataValueProcessor(): RequestDataValueProcessor = CsrfRequestDataValueProcessor()

        @Bean
        fun webSecurityExpressionHandler() =
            org.springframework.security.web.access.expression.DefaultWebSecurityExpressionHandler()
    }

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var materialService: MaterialService
    @MockBean private lateinit var danceTypeService: DanceTypeService
    @MockBean private lateinit var danceCategoryService: DanceCategoryService
    @MockBean private lateinit var commentService: CommentService
    @MockBean private lateinit var danceFigureService: DanceFigureService
    @MockBean private lateinit var appUserService: AppUserService
    @MockBean private lateinit var noteRewriteService: NoteRewriteService
    @MockBean private lateinit var figureSuggestionService: FigureSuggestionService
    @MockBean private lateinit var trainingEventService: TrainingEventService
    @MockBean private lateinit var assistantDrafts: AssistantDraftService
    // Global NavbarAdvice and interceptor dependencies
    @MockBean private lateinit var customListService: CustomListService
    @MockBean private lateinit var activityEventService: ActivityEventService
    @MockBean private lateinit var systemSettingService: SystemSettingService
    @MockBean private lateinit var calendarSyncService: CalendarSyncService
    @MockBean private lateinit var activeCalendarService: ActiveCalendarService

    private val draftId = UUID.randomUUID()
    private val category = DanceCategory().apply { id = UUID.randomUUID(); name = "Standard" }
    private val figure = DanceFigure().apply { id = UUID.randomUUID(); name = "Feather Step"; alternativeTiming = "S Q Q" }
    private val session = TrainingEvent().apply {
        id = UUID.randomUUID(); title = "Standard group class"
        startTime = LocalDateTime.of(2026, 9, 23, 19, 0); endTime = LocalDateTime.of(2026, 9, 23, 20, 30)
    }
    private val savedNote = Material().apply { id = UUID.randomUUID() }

    @BeforeEach
    fun setUp() {
        val user = AppUser().apply { id = UUID.randomUUID(); username = "dancer"; displayName = "Dancer" }
        `when`(appUserService.getCurrentUser()).thenReturn(user)
        `when`(danceCategoryService.findAll()).thenReturn(listOf(category))
        `when`(danceTypeService.findByCategoryId(category.id!!)).thenReturn(emptyList())
        `when`(danceFigureService.findById(figure.id!!)).thenReturn(figure)
        `when`(trainingEventService.findById(session.id!!)).thenReturn(session)
        `when`(materialService.create(anyRequest())).thenReturn(savedNote)
        `when`(trainingEventService.bulkUpdateMaterial(listOf(session.id!!), savedNote.id, null, false))
            .thenReturn(BulkEditResult(updatedCount = 1))
    }

    private fun anyRequest(): MaterialRequest {
        org.mockito.Mockito.any<MaterialRequest>()
        return MaterialRequest(version = 0)
    }

    private fun draftRequest() = MaterialRequest(
        name = "Tuesday class", description = "<div>Head drops.</div>", danceCategoryId = category.id, version = 0,
        trainingEventId = session.id, figureIds = listOf(figure.id!!), markAttended = true
    )

    @Test
    fun `the note form opens prefilled from the draft, with the figures and attendance shown and carried`() {
        `when`(assistantDrafts.noteForForm(draftId)).thenReturn(draftRequest())

        val html = mockMvc.perform(get("/materials/new").param("fromDraft", draftId.toString()).with(csrf()))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val doc = Jsoup.parse(html)

        assertEquals("Tuesday class", doc.selectFirst("input[name=name]")?.attr("value"))
        assertEquals(session.id.toString(), doc.selectFirst("input[name=trainingEventId]")?.attr("value"))
        assertNotNull(doc.selectFirst("select[name=danceCategoryId] option[selected][value=${category.id}]"))
        // The form has no figure picker, so the pins show as chips and ride along in one hidden field.
        assertTrue(doc.select("[data-draft-figures]").text().contains("Feather Step"))
        assertTrue(doc.select("[data-draft-figures]").text().contains("S Q Q"))
        assertEquals(figure.id.toString(), doc.selectFirst("input[name=figureIds]")?.attr("value"))
        assertEquals("true", doc.selectFirst("input[name=markAttended]")?.attr("value"))
        assertTrue(doc.select("[data-draft-session]").text().contains("Standard group class"))
        assertEquals(1, doc.select("input[name=figureIds]").size, "one field, so no form posts a name twice")
    }

    @Test
    fun `a figure that can no longer be read is left out of the chips and of the hidden field`() {
        val gone = UUID.randomUUID()
        `when`(danceFigureService.findById(gone)).thenThrow(EntityNotFoundException("gone"))
        `when`(assistantDrafts.noteForForm(draftId)).thenReturn(draftRequest().copy(figureIds = listOf(gone, figure.id!!)))

        val doc = Jsoup.parse(
            mockMvc.perform(get("/materials/new").param("fromDraft", draftId.toString()).with(csrf())).andReturn().response.contentAsString
        )

        assertEquals(figure.id.toString(), doc.selectFirst("input[name=figureIds]")?.attr("value"))
    }

    @Test
    fun `a draft that is missing, foreign or saved opens a blank form`() {
        `when`(assistantDrafts.noteForForm(draftId)).thenReturn(null)

        val doc = Jsoup.parse(
            mockMvc.perform(get("/materials/new").param("fromDraft", draftId.toString()).with(csrf())).andReturn().response.contentAsString
        )

        assertEquals("", doc.selectFirst("input[name=name]")?.attr("value") ?: "")
        assertNull(doc.selectFirst("[data-draft-figures]"))
        assertNull(doc.selectFirst("input[name=markAttended]"))
    }

    @Test
    fun `a blank new note form carries no draft fields`() {
        val doc = Jsoup.parse(mockMvc.perform(get("/materials/new").with(csrf())).andReturn().response.contentAsString)

        assertNull(doc.selectFirst("input[name=figureIds]"))
        assertNull(doc.selectFirst("input[name=markAttended]"))
    }

    @Test
    fun `saving the form pins the carried figures, records attendance and links the session`() {
        mockMvc.perform(
            post("/materials").param("name", "Tuesday class").param("version", "0")
                .param("trainingEventId", session.id.toString())
                .param("figureIds", figure.id.toString()).param("markAttended", "true").with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${savedNote.id}"))

        verify(materialService).addFigure(savedNote.id!!, FigureRequest(danceFigureId = figure.id))
        verify(trainingEventService).updateAttendance(session.id!!, AttendanceStatus.ATTENDED)
        verify(trainingEventService).bulkUpdateMaterial(listOf(session.id!!), savedNote.id, null, false)
    }

    @Test
    fun `a pin or attendance that fails does not lose the saved note`() {
        doThrow(IllegalStateException("pin failed")).`when`(materialService).addFigure(savedNote.id!!, FigureRequest(danceFigureId = figure.id))
        doThrow(IllegalStateException("attendance failed")).`when`(trainingEventService).updateAttendance(session.id!!, AttendanceStatus.ATTENDED)

        mockMvc.perform(
            post("/materials").param("name", "Tuesday class").param("version", "0")
                .param("trainingEventId", session.id.toString())
                .param("figureIds", figure.id.toString()).param("markAttended", "true").with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/materials/${savedNote.id}"))
    }

    @Test
    fun `a form without draft fields behaves exactly as before`() {
        mockMvc.perform(post("/materials").param("name", "Just a note").param("version", "0").with(csrf()))
            .andExpect(redirectedUrl("/materials"))

        verify(materialService, never()).addFigure(savedNote.id!!, FigureRequest(danceFigureId = figure.id))
        verify(trainingEventService, never()).updateAttendance(session.id!!, AttendanceStatus.ATTENDED)
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.MaterialFromDraftWebTest"`
Expected: FAIL (no `fromDraft` handling, no `draftFigures`, `addFigure` never called).

- [ ] **Step 3: Change the note controller**

In `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/MaterialWebController.kt`:

Add imports if missing: `com.jankowski.rafal.dancebook.model.AttendanceStatus`, `com.jankowski.rafal.dancebook.service.AssistantDraftService`, `org.springframework.beans.factory.annotation.Autowired`.

Add the optional field next to the other dependencies (inside the class body, not the constructor):

```kotlin
    /** Present only when the assistant is (#149). The form reads an "Edit in form" draft through it. */
    @Autowired(required = false)
    var assistantDrafts: AssistantDraftService? = null
```

Replace `showCreateForm` with:

```kotlin
    @GetMapping("/new")
    fun showCreateForm(
        @RequestParam(required = false) fromSession: UUID?,
        @RequestParam(required = false) fromDraft: UUID?,
        model: Model
    ): String {
        val drafted = fromDraft?.let { assistantDrafts?.noteForForm(it) }
        if (drafted != null) return showDraftForm(drafted, model)
        val session = fromSession?.let { runCatching { trainingEventService.findById(it) }.getOrNull() }
        val categoryId = session?.segments?.firstNotNullOfOrNull { it.danceCategory?.id }
        model.addAttribute(
            "material",
            MaterialRequest(
                name = session?.let { "${it.title} — ${it.startTime.format(SESSION_DATE_FORMAT)}" } ?: "",
                danceCategoryId = categoryId,
                version = 0,
                trainingEventId = session?.id
            )
        )
        model.addAttribute(
            "danceTypes",
            categoryId?.let { danceTypeService.findByCategoryId(it) } ?: emptyList<DanceType>()
        )
        populateDropdowns(model)
        return "materials/form"
    }

    /**
     * The form for a note the assistant drafted ("Edit in form"). The form has no figure picker,
     * so the draft's pins show as chips and travel as one hidden field, and the attendance choice
     * as another, both applied when this form is saved. A figure or session the user can no longer
     * read is left out of both the chips and the hidden field.
     */
    private fun showDraftForm(draft: MaterialRequest, model: Model): String {
        val figures = draft.figureIds.mapNotNull { runCatching { danceFigureService.findById(it) }.getOrNull() }
        val session = draft.trainingEventId?.let { runCatching { trainingEventService.findById(it) }.getOrNull() }
        model.addAttribute(
            "material",
            draft.copy(
                figureIds = figures.mapNotNull { it.id },
                trainingEventId = session?.id,
                markAttended = draft.markAttended && session != null
            )
        )
        model.addAttribute("draftFigures", figures)
        model.addAttribute("draftSession", session)
        model.addAttribute("danceTypes", draft.danceCategoryId?.let { danceTypeService.findByCategoryId(it) } ?: emptyList<DanceType>())
        populateDropdowns(model)
        return "materials/form"
    }
```

Replace `createMaterial`'s success section:

```kotlin
        val material = materialService.create(request)
        val sessionId = request.trainingEventId
        if (sessionId != null) {
            linkToSession(sessionId, material.id!!)
            // The note page is where figures get pinned, so land there rather than on the list.
            return "redirect:/materials/${material.id}"
        }
        return "redirect:/materials"
```

with:

```kotlin
        val material = materialService.create(request)
        applyDraftExtras(material.id!!, request)
        val sessionId = request.trainingEventId
        if (sessionId != null) {
            linkToSession(sessionId, material.id!!)
            // The note page is where figures get pinned, so land there rather than on the list.
            return "redirect:/materials/${material.id}"
        }
        return if (request.figureIds.isNotEmpty()) "redirect:/materials/${material.id}" else "redirect:/materials"
```

Add, next to `linkToSession`:

```kotlin
    /**
     * What an assistant draft carries that the form cannot edit: figures to pin, and attendance for
     * the linked session. Like the session link, each is best effort: the note is already saved
     * and is the user's work, so a failed pin or attendance is logged, not allowed to lose it.
     */
    private fun applyDraftExtras(materialId: UUID, request: MaterialRequest) {
        request.figureIds.distinct().forEach { figureId ->
            try {
                materialService.addFigure(materialId, FigureRequest(danceFigureId = figureId))
            } catch (e: Exception) {
                log.warn("Note {} was saved but figure {} could not be pinned", materialId, figureId, e)
            }
        }
        val sessionId = request.trainingEventId
        if (request.markAttended && sessionId != null) {
            try {
                trainingEventService.updateAttendance(sessionId, AttendanceStatus.ATTENDED)
            } catch (e: Exception) {
                log.warn("Note {} was saved but session {} could not be marked attended", materialId, sessionId, e)
            }
        }
    }
```

(`FigureRequest` and `log` are already used in this file. If `FigureRequest` is not imported, it is in `com.jankowski.rafal.dancebook.dto`.)

- [ ] **Step 4: Add the chips and hidden fields to the note form**

In `src/main/resources/templates/materials/form.html`, directly after the two hidden inputs (`version` and `trainingEventId`), add:

```html
        <!-- From an assistant draft ("Edit in form"): this form has no figure picker, so the drafted pins and
             the attendance choice are shown here and carried as hidden fields, applied when it is saved. -->
        <th:block th:if="${draftFigures != null and !#lists.isEmpty(draftFigures)}">
            <input type="hidden" name="figureIds" th:value="${#strings.listJoin(material.figureIds, ',')}"/>
            <div data-draft-figures class="flex flex-col gap-2">
                <span class="form-label">Figures from the assistant</span>
                <ul class="m-0 flex list-none flex-wrap gap-2 p-0">
                    <li th:each="fig : ${draftFigures}" class="flex items-center gap-2 rounded-full border border-outline px-3 py-1 text-sm">
                        <span th:text="${fig.name}">Feather Step</span>
                        <span th:if="${fig.alternativeTiming != null and !#strings.isEmpty(fig.alternativeTiming)}"
                              class="font-semibold text-primary" th:text="${fig.alternativeTiming}">S Q Q</span>
                    </li>
                </ul>
                <p class="text-xs text-on-surface-variant m-0">They are pinned to the note when you save it.</p>
            </div>
        </th:block>
        <th:block th:if="${draftSession != null}">
            <div data-draft-session class="text-sm text-on-surface-variant">
                Written from <span class="font-semibold text-on-surface" th:text="${draftSession.title}">Standard group class</span>
                <span th:if="${material.markAttended}">; it will be marked attended when you save.</span>
            </div>
            <input type="hidden" name="markAttended" th:if="${material.markAttended}" value="true"/>
        </th:block>
```

- [ ] **Step 5: Run the note-form tests and the existing ones for that controller**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.MaterialFromDraftWebTest" --tests "com.jankowski.rafal.dancebook.controller.web.MaterialFromSessionWebTest" --tests "com.jankowski.rafal.dancebook.controller.web.FormValidationWebTest"`
Expected: PASS. `MaterialFromSessionWebTest`'s "a note saved without a session" asserts `redirectedUrl("/materials")`; the new `return if (...)` keeps that (no `figureIds`).

- [ ] **Step 6: Write the failing session and figure form tests** (added to the two existing controller test classes)

Both controllers are already unit-tested by constructing them by hand (`controller`, and the mocks for their services, exist in each class's `setUp`), so the new tests go into those two classes. Add them:

```kotlin
// In DanceFigureWebControllerTest (needs `import com.jankowski.rafal.dancebook.service.AssistantDraftService`, `import com.jankowski.rafal.dancebook.dto.DanceFigureRequest` is already imported):
    @Test
    fun `the figure form opens prefilled from an assistant draft`() {
        val draftId = UUID.randomUUID()
        val drafts = mock(AssistantDraftService::class.java)
        val typeId = UUID.randomUUID()
        `when`(danceFigureService.findByDanceType(typeId)).thenReturn(emptyList())
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        `when`(drafts.figureForForm(draftId)).thenReturn(
            DanceFigureRequest(name = "Heel Turn", danceTypeId = typeId, alternativeTiming = "1 2 3")
        )
        controller.assistantDrafts = drafts
        val model = org.springframework.ui.ConcurrentModel()

        val view = controller.showCreateForm(null, draftId, model)

        assertEquals("dance-figures/form", view)
        val form = model.getAttribute("danceFigure") as DanceFigureRequest
        assertEquals("Heel Turn", form.name)
        assertEquals("1 2 3", form.alternativeTiming)
    }

    @Test
    fun `a missing or foreign figure draft opens the blank form`() {
        `when`(danceTypeService.findAll()).thenReturn(emptyList())
        val drafts = mock(AssistantDraftService::class.java)
        controller.assistantDrafts = drafts
        val model = org.springframework.ui.ConcurrentModel()

        controller.showCreateForm(null, UUID.randomUUID(), model)

        assertEquals("", (model.getAttribute("danceFigure") as DanceFigureRequest).name)
    }
```

```kotlin
// In TrainingEventWebControllerTest (needs `import com.jankowski.rafal.dancebook.service.AssistantDraftService`; setUp already stubs
// activeCalendarService.creationTarget() to `defaultCal`):
    @Test
    fun `the session form opens prefilled from an assistant draft, aimed at the creation calendar`() {
        val draftId = UUID.randomUUID()
        val drafts = mock(AssistantDraftService::class.java)
        `when`(drafts.sessionForForm(draftId)).thenReturn(
            TrainingEventRequest(
                title = "Practice", date = java.time.LocalDate.of(2026, 10, 3),
                startTime = java.time.LocalTime.of(10, 0), endTime = java.time.LocalTime.of(12, 0),
                calendarId = UUID.randomUUID()
            )
        )
        controller.assistantDrafts = drafts
        val model = org.springframework.ui.ConcurrentModel()

        val view = controller.showCreateForm(draftId, model)

        assertEquals("training-events/form", view)
        val form = model.getAttribute("trainingEvent") as TrainingEventRequest
        assertEquals("Practice", form.title)
        assertEquals(java.time.LocalTime.of(10, 0), form.startTime)
        assertEquals(defaultCal.id, form.calendarId, "the draft is aimed at the calendar the user would create into now")
    }
```

- [ ] **Step 7: Run them to see them fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.DanceFigureWebControllerTest" --tests "com.jankowski.rafal.dancebook.controller.web.TrainingEventWebControllerTest"`
Expected: compile FAIL (`assistantDrafts` and the `fromDraft` parameter do not exist on those controllers).

- [ ] **Step 8: Change the session and figure controllers**

In `DanceFigureWebController.kt` add `import com.jankowski.rafal.dancebook.service.AssistantDraftService` and `import org.springframework.beans.factory.annotation.Autowired`, the field:

```kotlin
    /** Present only when the assistant is (#149). The form reads an "Edit in form" draft through it. */
    @Autowired(required = false)
    var assistantDrafts: AssistantDraftService? = null
```

and replace `showCreateForm`:

```kotlin
    @GetMapping("/new")
    fun showCreateForm(
        @RequestParam(required = false) danceTypeId: UUID?,
        @RequestParam(required = false) fromDraft: UUID?,
        model: Model
    ): String {
        val drafted = fromDraft?.let { assistantDrafts?.figureForForm(it) }
        val form = drafted ?: DanceFigureRequest(danceTypeId = danceTypeId)
        val availableFigures = form.danceTypeId?.let { danceFigureService.findByDanceType(it) } ?: emptyList()
        model.addAttribute("danceFigure", form)
        model.addAttribute("availableFigures", availableFigures)
        model.addAttribute("danceTypes", danceTypeService.findAll())
        model.addAttribute("danceClasses", DanceClass.values())
        return "dance-figures/form"
    }
```

(No existing test calls the figure controller's `showCreateForm`, so nothing else changes there.)

In `TrainingEventWebController.kt` add the same import and field, and replace `showCreateForm`:

```kotlin
    @GetMapping("/new")
    fun showCreateForm(
        @RequestParam(required = false) fromDraft: UUID?,
        model: Model
    ): String {
        populateFormOptions(model)
        val targetCalendar = try {
            activeCalendarService.creationTarget()
        } catch (e: CalendarSyncException) {
            model.addAttribute("calendarError", e.message)
            null
        }
        model.addAttribute("targetCalendar", targetCalendar)
        // A draft is aimed at the calendar the user would create into right now, not the one it was drafted for.
        val drafted = fromDraft?.let { assistantDrafts?.sessionForForm(it) }
        model.addAttribute(
            "trainingEvent",
            drafted?.copy(calendarId = targetCalendar?.id) ?: TrainingEventRequest(calendarId = targetCalendar?.id)
        )
        return "training-events/form"
    }
```

In `TrainingEventWebControllerTest`, the three existing calls `controller.showCreateForm(model)` (in `showCreateForm populates form options and names target calendar on request`, `showCreateForm names active calendar when specific calendar is active` and `showCreateForm records error when active calendar is disabled`) become `controller.showCreateForm(null, model)`.

- [ ] **Step 9: Run the form tests, the smoke test and the guards**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.controller.web.DanceFigureWebControllerTest" --tests "com.jankowski.rafal.dancebook.controller.web.TrainingEventWebControllerTest" --tests "com.jankowski.rafal.dancebook.controller.web.MaterialFromDraftWebTest" --tests "com.jankowski.rafal.dancebook.controller.web.WebRouteSmokeTest" --tests "com.jankowski.rafal.dancebook.controller.web.FormValidationWebTest"`
Expected: PASS. `WebRouteSmokeTest` requests `/materials/new`, `/training-events/new` and `/dance-figures/new` with no `fromDraft`; all must still be 200 and must not post any field name twice.

- [ ] **Step 10: Commit**

```bash
git add src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/MaterialWebController.kt src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebController.kt src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/DanceFigureWebController.kt src/main/resources/templates/materials/form.html src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/MaterialFromDraftWebTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/DanceFigureWebControllerTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/TrainingEventWebControllerTest.kt
git commit -m "feat: open the create forms prefilled from an assistant draft (#149)"
```

---

### Task 9: The "Wrap up" chip on the dashboard

**Files:**
- Modify: `src/main/resources/templates/home/wrap-up.html`
- Modify: `src/main/resources/static/js/assistant.js`
- Modify: `src/test/kotlin/com/jankowski/rafal/dancebook/frontend/AssistantScriptGuardTest.kt`
- Modify: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/HomeDashboardRenderingTest.kt`

**Interfaces:**
- Consumes: `assistantNav` (set by `NavbarAdvice` only when the assistant is on and a user is signed in), `wrapUp.hero` (`title`, `dateLabel`).
- Produces: a button with `data-assistant-prefill="Wrap up \"<title>\" (<date>): "` in the wrap-up hero; `assistant.js` opens the assistant and puts that text in the composer, focused, **without sending**, so the user adds what happened.

- [ ] **Step 1: Write the failing tests**

Add to `AssistantScriptGuardTest.kt`:

```kotlin
    @Test
    fun `a prefill chip opens the assistant and fills the composer without sending`() {
        assertTrue(script.contains("[data-assistant-prefill]"))
        val branch = script.substringAfter("[data-assistant-prefill]").substringBefore("const prompt")
        assertTrue(branch.contains("openSurface()"), "it opens the surface")
        assertTrue(branch.contains("composerInput.value"), "it fills the composer")
        assertFalse(branch.contains("requestSubmit"), "it must not send: the user still has to say what happened")
    }
```

Add to `HomeDashboardRenderingTest.kt` (new imports: `com.jankowski.rafal.dancebook.dto.PageContext`, `com.jankowski.rafal.dancebook.dto.PageContextType`, `com.jankowski.rafal.dancebook.service.AssistantNav`, `com.jankowski.rafal.dancebook.service.AssistantNavSupport`, `org.jsoup.Jsoup`, `org.junit.jupiter.api.AfterEach`, `org.springframework.security.authentication.UsernamePasswordAuthenticationToken`, `org.springframework.security.core.authority.SimpleGrantedAuthority`, `org.springframework.security.core.context.SecurityContextImpl`, `org.springframework.security.test.context.TestSecurityContextHolder`), a `@MockBean private lateinit var assistantNavSupport: AssistantNavSupport` with the other mocks, and:

```kotlin
    @AfterEach
    fun clearSecurityContext() {
        TestSecurityContextHolder.clearContext()
    }

    /** `NavbarAdvice` only supplies `assistantNav` for an authenticated principal; with the filters off the test has to provide one. */
    private fun signedIn() {
        TestSecurityContextHolder.setContext(
            SecurityContextImpl(UsernamePasswordAuthenticationToken("dancer", "x", listOf(SimpleGrantedAuthority("ROLE_USER"))))
        )
    }

    @Test
    fun `the wrap-up hero offers to wrap the session up with the assistant, prefilled and not sent`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView())
        `when`(assistantNavSupport.forPath("/")).thenReturn(AssistantNav(PageContext(PageContextType.HOME), "Home"))
        signedIn()

        val doc = Jsoup.parse(mockMvc.perform(get("/").with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString)

        val chip = doc.selectFirst("#home-wrap-up [data-assistant-prefill]")!!
        assertEquals("Wrap up \"Standard group class\" (Tuesday 23 Sep): ", chip.attr("data-assistant-prefill"))
    }

    @Test
    fun `without the assistant the hero has no wrap-up chip`() {
        `when`(dashboardService.dashboardForCurrentUser()).thenReturn(populatedView())
        signedIn()

        val doc = Jsoup.parse(mockMvc.perform(get("/").with(csrf())).andExpect(status().isOk).andReturn().response.contentAsString)

        assertNull(doc.selectFirst("[data-assistant-prefill]"))
    }
```

(Add `assertEquals` and `assertNull` imports from `org.junit.jupiter.api.Assertions`.)

- [ ] **Step 2: Run them to see them fail**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.frontend.AssistantScriptGuardTest" --tests "com.jankowski.rafal.dancebook.controller.web.HomeDashboardRenderingTest"`
Expected: FAIL (no chip; the script has no prefill branch).

- [ ] **Step 3: Add the chip**

In `src/main/resources/templates/home/wrap-up.html`, inside the hero's action row (`<div class="grid grid-cols-2 lg:flex gap-2">`), after the `Skipped` form and before the two note links, add:

```html
            <th:block th:if="${assistantNav != null}">
                <!--/* Single-quoted attribute so the literal substitution can hold double quotes; Thymeleaf escapes them in the output. */-->
                <button type="button"
                        th:attr='data-assistant-prefill=|Wrap up "${wrapUp.hero.title}" (${wrapUp.hero.dateLabel}): |'
                        class="col-span-2 lg:col-span-1 min-h-[44px] px-5 rounded-xl border border-on-primary/40 text-on-primary text-sm font-medium flex items-center justify-center gap-2 cursor-pointer hover:bg-on-primary/10">
                    <span th:replace="~{fragments/icon :: icon(name='auto_awesome', size='md', title=null)}"></span>Wrap up with the assistant
                </button>
            </th:block>
```

- [ ] **Step 4: Add the script branch**

In `src/main/resources/static/js/assistant.js`, in the `document.addEventListener('click', ...)` handler, insert between the `opener` branch and the `prompt` branch:

```js
        const prefill = event.target.closest('[data-assistant-prefill]');
        if (prefill) {
            // Wrapping up needs the user's words (what was covered), so this only starts the sentence.
            openSurface();
            composerInput.value = prefill.getAttribute('data-assistant-prefill');
            composerInput.focus();
            composerInput.setSelectionRange(composerInput.value.length, composerInput.value.length);
            return;
        }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests "com.jankowski.rafal.dancebook.frontend.AssistantScriptGuardTest" --tests "com.jankowski.rafal.dancebook.controller.web.HomeDashboardRenderingTest" --tests "com.jankowski.rafal.dancebook.controller.web.ThymeleafConditionalIncludeTest" --tests "com.jankowski.rafal.dancebook.controller.web.ThymeleafRestrictedExpressionTest" --tests "com.jankowski.rafal.dancebook.controller.web.HtmxFragmentRenderingTest"`
Expected: PASS. If the prefill text does not round-trip with its double quotes, print the rendered `<button>` and fix the attribute's quoting; the test's expected string is the contract.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/home/wrap-up.html src/main/resources/static/js/assistant.js src/test/kotlin/com/jankowski/rafal/dancebook/frontend/AssistantScriptGuardTest.kt src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/HomeDashboardRenderingTest.kt
git commit -m "feat: start a wrap-up from the dashboard hero with the assistant (#149)"
```

---

### Task 10: Whole build, manual check and pull request

**Files:** none

- [ ] **Step 1: Run the whole build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. Docker must be running (Testcontainers). If a test outside this plan fails, read it before changing anything: the likeliest causes are an existing test that constructs `AssistantServiceImpl` or a controller directly, and a template guard naming a line in the new markup.

- [ ] **Step 2: Confirm the acceptance criteria against the tests**

Check each against the test that proves it and stop if any has none:

| Acceptance criterion | Proven by |
|---|---|
| No draft tool writes to any domain table; only Save does | `AssistantDraftLoopTest` ("a draft tool never writes"), `AssistantDraftToolsTest` (fake service only) |
| Save calls the same service as the form, publishes its event, creates the Google event | `AssistantDraftServiceImplTest` (call order; `validateCreationTarget` then `create`), `AssistantDraftServiceIntegrationTest` |
| A second Save is refused; another user's draft is 404 | `AssistantDraftStoreIntegrationTest`, `AssistantDraftServiceIntegrationTest`, `AssistantDraftWebIntegrationTest` |
| An invented id is dropped (stubbed `ChatModel`) | `AssistantDraftLoopTest` |
| `draft_note` with `markAttended` writes the `TrainingRecord` and links the session | `AssistantDraftServiceIntegrationTest` |
| Edit in form opens the right form with every field prefilled | `MaterialFromDraftWebTest`, the figure and session tests in Task 8 |
| A validation or calendar-sync failure leaves the draft `PENDING` with the error on the card | `AssistantDraftServiceImplTest`, `AssistantDraftServiceIntegrationTest`, `AssistantFragmentRenderingTest` |
| `./gradlew build` is green | Step 1 |

- [ ] **Step 3: Write the manual-verification checklist for the user.** The real Gemini model is credential-gated, so do not ask for a key; hand back this list.

Reply to the user with: "Everything above is covered by tests with a scripted model. What tests cannot show is how Gemini behaves with the new tools and how the cards look. With your `GOOGLE_AI_API_KEY` set and `./gradlew bootRun`, please check:
1. Say "add a note: Tuesday's class, feather step, head dropping" → a note card with Feather Step and its timing; **Save** turns it into a link to the note; the note page shows the pinned figure.
2. Say "add practice Saturday 10–12, quickstep then tango" → a session card; **Save** creates it and the event appears in Google Calendar.
3. On the home page, **Wrap up with the assistant** on the hero → the composer fills with the start of a sentence; finish it and send; the note card shows the session and "Mark the session as attended"; **Save** → the session shows attended and the note is linked.
4. **Edit in form** on a note draft → the form opens with the text, style, session and the figure chips, and saving it pins the figure; the card above greys out.
5. Click **Save** twice quickly, or in two tabs → one note only."

- [ ] **Step 4: Push and open the pull request** (only after the user says to; do not push unasked)

Ask the user whether to push `feat/ai-assistant-drafts-149` and open the PR. When they agree:

```bash
git push -u origin feat/ai-assistant-drafts-149
gh pr create --title "feat: confirmed drafts for the assistant (#149)" --body "Closes #149. The assistant drafts notes, sessions and figures; Save goes through the same services as the forms, once; Edit in form opens the create form prefilled. See docs/superpowers/plans/2026-09-30-ai-assistant-drafts.md."
```

No attribution lines in the body. After it merges, the documentation pull request ("docs: describe the assistant drafts that #149 shipped", updating `AGENTS.md`) follows, as for every shipped phase.
