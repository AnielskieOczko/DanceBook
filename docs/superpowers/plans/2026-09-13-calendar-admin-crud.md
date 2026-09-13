# Calendar Admin CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the admin calendars section the edit and delete it currently lacks, with a reusable server-rendered confirm dialog.

**Architecture:** Inline row editing reuses the `userRow` / `editUserRow` fragment-pair pattern already in the admin dashboard. Deleting a calendar removes its sessions through `TrainingEventPersistence` so their history is orphaned rather than erased, and never calls Google. The confirm dialog is built as a shared fragment because issue #47 needs the same component for bulk actions.

**Tech Stack:** Kotlin 1.9 / Java 21, Spring Boot 3.5 (Web MVC, Data JPA, Thymeleaf), PostgreSQL + Flyway, HTMX 2, Tailwind 3, JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-09-13-calendar-as-view-context-design.md` (the "Admin CRUD" section)

**Independent of** the calendar-view-context plan — neither needs the other, and they can be built in either order or in parallel.

## Global Constraints

- **No schema change is needed.** This plan adds no entity fields, so it adds no Flyway migration.
- **Mutating service method ⇒ publish the matching `DomainEvent`.** Calendar administration publishes none, matching `SystemSettingService` and the existing admin user endpoints — `activity_event` records what happens to training *sessions*, not app configuration. Deleting a calendar removes sessions through `TrainingEventPersistence`, which already publishes the session-level events itself; do not add calendar-level ones.
- **Colours come from the Noble Harmony tokens** in `src/main/resources/frontend/tailwind.config.js`. Tailwind scans templates only, so every class must appear in a template — never assembled in JavaScript. Never edit generated `static/css/output.css`.
- **CSP forbids inline `<script>` and `onclick`.** Behaviour lives in `src/main/resources/static/js/*.js`. `static/js/main.js` already adds the CSRF header on `htmx:configRequest`.
- **Google is never called when deleting a calendar.** The Google calendar and its events are not ours to destroy; we remove our mirror only.
- Run `./gradlew build` before every commit. Docker must be running for Testcontainers tests.

---

### Task 1: Shared confirm dialog

The existing `data-confirm` hook in `static/js/main.js` is a bare `window.confirm` and cannot show a count or a warning. Issue #47 needs the same component for bulk actions, so this is built shared rather than admin-specific.

**Files:**
- Create: `src/main/resources/templates/fragments/confirm.html`
- Create: `src/main/resources/static/js/confirm-dialog.js`
- Modify: `src/main/resources/templates/layout.html`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/ConfirmDialogRenderingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a `confirmDialog` fragment taking `title`, `message`, `confirmLabel`, and the htmx attributes for the confirming action; and a `#confirm-dialog-host` container in the layout that fragments swap into.

- [ ] **Step 1: Write the failing rendering test**

```kotlin
package com.jankowski.rafal.dancebook.controller.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class ConfirmDialogRenderingTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @Test
    @WithMockUser(roles = ["ADMIN"])
    fun `every page carries a confirm dialog host for fragments to swap into`() {
        val html = mockMvc.perform(get("/admin"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertTrue(html.contains("id=\"confirm-dialog-host\""))
    }
}
```

Wire this test the same way the existing `TrainingEventViewRenderingTest` is wired in this project — copy its `@SpringBootTest` annotations, any `@MockBean` declarations it needs to avoid live Google calls, and its Testcontainers or test-properties setup.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "*ConfirmDialogRenderingTest*"`
Expected: FAIL — the host element is absent.

- [ ] **Step 3: Add the host to the layout**

In `src/main/resources/templates/layout.html`, immediately before the closing `</body>`:

```html
<div id="confirm-dialog-host"></div>
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew test --tests "*ConfirmDialogRenderingTest*"`
Expected: PASS.

- [ ] **Step 5: Write the dialog fragment**

Create `src/main/resources/templates/fragments/confirm.html`. Every class here is already used elsewhere in the app, so Tailwind's template-only scan covers them.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>

<!--
  A server-rendered confirm. The data-confirm hook in main.js is a bare window.confirm and
  cannot show a count or a warning, and CSP forbids inline script, so the dialog is markup
  swapped into #confirm-dialog-host and dismissed by the delegated handler in
  static/js/confirm-dialog.js.

  Callers supply the action attributes on the confirm button, so this fragment stays unaware
  of what it is confirming.
-->
<div th:fragment="confirmDialog(title, message, confirmLabel, confirmAttrs)"
     class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
     data-confirm-dialog>
    <div class="card max-w-md w-full p-6">
        <h3 class="font-heading font-semibold text-lg text-text-primary mb-2" th:text="${title}">Are you sure?</h3>
        <p class="text-sm text-text-secondary mb-6" th:text="${message}">This cannot be undone.</p>
        <div class="flex justify-end gap-2">
            <button type="button" class="btn-outline btn-sm" data-confirm-cancel>Cancel</button>
            <button type="button" class="btn-danger btn-sm"
                    th:attrappend="hx-post=${confirmAttrs}"
                    hx-target="#calendarsSection" hx-swap="outerHTML"
                    th:text="${confirmLabel}">Delete</button>
        </div>
    </div>
</div>

<div th:fragment="confirmDismissed"></div>

</body>
</html>
```

Check that `btn-danger` exists in `src/main/resources/frontend/input.css`; if it does not, use the class the app already uses for destructive buttons and keep this fragment consistent with it.

- [ ] **Step 6: Write the dismiss handler**

Create `src/main/resources/static/js/confirm-dialog.js`:

```javascript
// Extracted to a file to comply with CSP, which allows no inline script or onclick.
// Delegated so it works for dialogs swapped in by htmx after page load.
document.addEventListener('click', function (event) {
    if (event.target.closest('[data-confirm-cancel]')) {
        const host = document.getElementById('confirm-dialog-host');
        if (host) host.innerHTML = '';
    }
});

document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') return;
    const host = document.getElementById('confirm-dialog-host');
    if (host && host.querySelector('[data-confirm-dialog]')) host.innerHTML = '';
});
```

Register it in `layout.html` alongside the existing `main.js` script tag:

```html
<script th:src="@{/js/confirm-dialog.js}" defer></script>
```

- [ ] **Step 7: Build and commit**

```bash
./gradlew build
git add src/main/resources/templates/fragments/confirm.html \
        src/main/resources/templates/layout.html \
        src/main/resources/static/js/confirm-dialog.js \
        src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/ConfirmDialogRenderingTest.kt
git commit -m "feat: add a server-rendered confirm dialog fragment"
```

---

### Task 2: Edit a calendar

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarService.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AdminCalendarController.kt`
- Modify: `src/main/resources/templates/admin/dashboard.html`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarServiceTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/AdminCalendarControllerTest.kt`

**Interfaces:**
- Consumes: `TrainingEventRepository.calendarIdsInUse()` — **defined in Task 2 of the calendar-view-context plan.** If that plan has not run, add it here instead:
  ```kotlin
  @Query("select distinct e.calendar.id from TrainingEvent e where e.calendar is not null")
  fun calendarIdsInUse(): List<UUID>
  ```
  Also `GoogleCalendarClient.verifyCalendar(calendarId: String): String` (already exists).
- Produces: `TrainingCalendarService.rename(id: UUID, displayName: String): TrainingCalendar`, `changeGoogleCalendarId(id: UUID, googleCalendarId: String): TrainingCalendar`, and `hasSessions(id: UUID): Boolean` (used by Step 6 so the template can disable the Google-ID input without the controller touching a repository).

- [ ] **Step 1: Write the failing service tests**

Add to `TrainingCalendarServiceTest.kt`:

```kotlin
    @Test
    fun `rename changes only the display name`() {
        val calendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "club@group.calendar.google.com"
            displayName = "Old name"
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(calendar.id!!)).thenReturn(Optional.of(calendar))
        `when`(trainingCalendarRepository.save(calendar)).thenReturn(calendar)

        val result = service.rename(calendar.id!!, "Club Training")

        assertEquals("Club Training", result.displayName)
        assertEquals("club@group.calendar.google.com", result.googleCalendarId)
    }

    @Test
    fun `the google calendar id cannot be changed while the calendar owns sessions`() {
        val calendar = TrainingCalendar().apply {
            id = UUID.randomUUID()
            googleCalendarId = "club@group.calendar.google.com"
            displayName = "Club Training"
            enabled = true
        }
        `when`(trainingCalendarRepository.findById(calendar.id!!)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.calendarIdsInUse()).thenReturn(listOf(calendar.id!!))

        val error = assertThrows(IllegalStateException::class.java) {
            service.changeGoogleCalendarId(calendar.id!!, "other@group.calendar.google.com")
        }
        assertEquals(
            "Club Training already has sessions, whose Google events live in the current " +
                "calendar. Add a new calendar instead of repointing this one.",
            error.message
        )
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests "*TrainingCalendarServiceTest*"`
Expected: FAIL — `rename` and `changeGoogleCalendarId` are unresolved.

- [ ] **Step 3: Add the interface methods**

In `TrainingCalendarService`:

```kotlin
    /** Renames the calendar. Always allowed — the display name has no external meaning. */
    fun rename(id: UUID, displayName: String): TrainingCalendar

    /**
     * Repoints the calendar at a different Google calendar, allowed only while it owns no
     * sessions: every stored google_event_id belongs to the current Google calendar and
     * would be stranded. The new id is verified, and a failure disables the calendar.
     */
    fun changeGoogleCalendarId(id: UUID, googleCalendarId: String): TrainingCalendar
```

- [ ] **Step 4: Implement them**

In `TrainingCalendarServiceImpl` — inject `private val trainingEventRepository: TrainingEventRepository` if it is not already a constructor parameter (it is, for the backfill), and `private val googleCalendarClient: GoogleCalendarClient`:

```kotlin
    @Transactional
    override fun rename(id: UUID, displayName: String): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        calendar.displayName = displayName.trim()
        calendar.updatedAt = LocalDateTime.now()
        return trainingCalendarRepository.save(calendar)
    }

    @Transactional
    override fun changeGoogleCalendarId(id: UUID, googleCalendarId: String): TrainingCalendar {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        if (id in trainingEventRepository.calendarIdsInUse()) {
            throw IllegalStateException(
                "${calendar.displayName} already has sessions, whose Google events live in the " +
                    "current calendar. Add a new calendar instead of repointing this one."
            )
        }
        val trimmed = googleCalendarId.trim()
        val clash = trainingCalendarRepository.findByGoogleCalendarId(trimmed)
        if (clash != null && clash.id != id) {
            throw IllegalArgumentException("A calendar with Google Calendar ID '$trimmed' already exists.")
        }
        calendar.googleCalendarId = trimmed
        calendar.enabled = runCatching { googleCalendarClient.verifyCalendar(trimmed) }.isSuccess
        calendar.updatedAt = LocalDateTime.now()
        return trainingCalendarRepository.save(calendar)
    }
```

- [ ] **Step 5: Run them to verify they pass**

Run: `./gradlew test --tests "*TrainingCalendarServiceTest*"`
Expected: PASS.

- [ ] **Step 6: Add the controller endpoints**

In `AdminCalendarController`, following the shape of its existing handlers — specific exception catches, never bare `Exception`, and returning the whole section:

```kotlin
    @GetMapping("/{id}/edit")
    fun showEditRow(@PathVariable id: UUID, model: Model): String {
        model.addAttribute("editCalendar", trainingCalendarService.findById(id))
        model.addAttribute("editCalendarHasSessions", trainingCalendarService.hasSessions(id))
        return "admin/dashboard :: editCalendarRow"
    }

    @GetMapping("/{id}/edit/cancel")
    fun cancelEditRow(@PathVariable id: UUID, model: Model): String {
        model.addAttribute("cal", trainingCalendarService.findById(id))
        return "admin/dashboard :: calendarRow"
    }

    @PostMapping("/{id}/edit")
    fun editCalendar(
        @PathVariable id: UUID,
        @RequestParam displayName: String,
        @RequestParam googleCalendarId: String,
        model: Model
    ): String {
        try {
            trainingCalendarService.rename(id, displayName)
            val current = trainingCalendarService.findById(id)
            if (current != null && current.googleCalendarId != googleCalendarId.trim()) {
                trainingCalendarService.changeGoogleCalendarId(id, googleCalendarId)
            }
        } catch (e: IllegalArgumentException) {
            model.addAttribute("calendarError", e.message)
        } catch (e: IllegalStateException) {
            model.addAttribute("calendarError", e.message)
        }
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }
```

Add `hasSessions(id: UUID): Boolean` to `TrainingCalendarService`, implemented as `id in trainingEventRepository.calendarIdsInUse()`, so the template can disable the Google-id input without the controller doing repository work.

- [ ] **Step 7: Split the calendar row into a fragment pair**

In `templates/admin/dashboard.html`, extract the existing `<tr th:each="cal : ${calendars}">` body from `calendarsSection` into a `calendarRow` fragment, iterated with `th:replace`, exactly as `usersSection` does with `userRow`. Then add the editing counterpart:

```html
<tr th:fragment="editCalendarRow" th:id="'calendar-row-' + ${editCalendar.id}" class="bg-primary-soft/20">
    <td class="table-cell">
        <input type="text" name="displayName" th:value="${editCalendar.displayName}" class="form-input" form="editCalendarForm">
    </td>
    <td class="table-cell">
        <input type="text" name="googleCalendarId" th:value="${editCalendar.googleCalendarId}"
               class="form-input font-mono" form="editCalendarForm"
               th:disabled="${editCalendarHasSessions}">
        <p th:if="${editCalendarHasSessions}" class="text-xs text-text-secondary mt-1">
            Cannot be repointed: this calendar already has sessions whose Google events live in it.
        </p>
    </td>
    <td class="table-cell" colspan="2"></td>
    <td class="table-cell text-right">
        <form id="editCalendarForm" th:hx-post="@{/admin/calendars/{id}/edit(id=${editCalendar.id})}"
              hx-target="#calendarsSection" hx-swap="outerHTML" class="flex justify-end gap-2">
            <button type="button" class="btn-outline btn-sm"
                    th:hx-get="@{/admin/calendars/{id}/edit/cancel(id=${editCalendar.id})}"
                    hx-target="closest tr" hx-swap="outerHTML">Cancel</button>
            <button type="submit" class="btn-primary btn-sm">Save</button>
        </form>
    </td>
</tr>
```

Add an Edit button to `calendarRow` targeting `closest tr` with `hx-swap="outerHTML"` and `hx-get="/admin/calendars/{id}/edit"`. Adjust the `colspan` above to match the actual column count of the table as it stands.

- [ ] **Step 8: Add controller tests**

Add to `AdminCalendarControllerTest.kt`, matching its existing style:

```kotlin
    @Test
    fun `editing returns the calendars section and reports a rejected id change`() {
        val id = UUID.randomUUID()
        `when`(trainingCalendarService.findById(id)).thenReturn(calendar(id, "club@group.calendar.google.com"))
        `when`(trainingCalendarService.changeGoogleCalendarId(id, "other@group.calendar.google.com"))
            .thenThrow(IllegalStateException("Club Training already has sessions"))
        val model = ConcurrentModel()

        val view = controller.editCalendar(id, "Club Training", "other@group.calendar.google.com", model)

        assertEquals("admin/dashboard :: calendarsSection", view)
        assertEquals("Club Training already has sessions", model.getAttribute("calendarError"))
    }
```

- [ ] **Step 9: Build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarService.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarServiceImpl.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AdminCalendarController.kt \
        src/main/resources/templates/admin/dashboard.html \
        src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarServiceTest.kt \
        src/test/kotlin/com/jankowski/rafal/dancebook/controller/web/AdminCalendarControllerTest.kt
git commit -m "feat: edit a training calendar from the admin dashboard"
```

---

### Task 3: Delete a calendar

**Files:**
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarService.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarServiceImpl.kt`
- Modify: `src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AdminCalendarController.kt`
- Modify: `src/main/resources/templates/admin/dashboard.html`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarServiceTest.kt`
- Test: `src/test/kotlin/com/jankowski/rafal/dancebook/service/TrainingCalendarDeleteIntegrationTest.kt`

**Interfaces:**
- Consumes: `TrainingEventPersistence.remove(event, actor)` (exists), `TrainingEventRepository`, `TrainingCalendarRepository`.
- Produces: `TrainingCalendarService.delete(id: UUID)` and `sessionCount(id: UUID): Long`.

- [ ] **Step 1: Add the repository query**

In `TrainingEventRepository`:

```kotlin
    fun findAllByCalendarId(calendarId: UUID): List<TrainingEvent>

    fun countByCalendarId(calendarId: UUID): Long
```

- [ ] **Step 2: Write the failing service tests**

Add to `TrainingCalendarServiceTest.kt`:

```kotlin
    @Test
    fun `deleting the default is rejected while other calendars exist`() {
        val default = TrainingCalendar().apply {
            id = UUID.randomUUID(); googleCalendarId = "a@g.com"; displayName = "Primary"
            isDefault = true; enabled = true
        }
        `when`(trainingCalendarRepository.findById(default.id!!)).thenReturn(Optional.of(default))
        `when`(trainingCalendarRepository.count()).thenReturn(2L)

        val error = assertThrows(IllegalStateException::class.java) { service.delete(default.id!!) }
        assertEquals("Make another calendar the default before deleting this one.", error.message)
    }

    @Test
    fun `deleting removes the calendar's sessions and never calls Google`() {
        val calendar = TrainingCalendar().apply {
            id = UUID.randomUUID(); googleCalendarId = "b@g.com"; displayName = "Club"
            isDefault = false; enabled = true
        }
        val session = TrainingEvent().apply { id = UUID.randomUUID(); this.calendar = calendar }
        `when`(trainingCalendarRepository.findById(calendar.id!!)).thenReturn(Optional.of(calendar))
        `when`(trainingEventRepository.findAllByCalendarId(calendar.id!!)).thenReturn(listOf(session))
        `when`(appUserService.getCurrentUser()).thenReturn(currentUser)

        service.delete(calendar.id!!)

        verify(trainingEventPersistence).remove(session, currentUser)
        verify(trainingCalendarRepository).delete(calendar)
        verifyNoInteractions(googleCalendarClient)
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew test --tests "*TrainingCalendarServiceTest*"`
Expected: FAIL — `delete` is unresolved.

- [ ] **Step 4: Implement delete**

In `TrainingCalendarService`:

```kotlin
    /** How many sessions the calendar owns; drives the confirm dialog's count. */
    fun sessionCount(id: UUID): Long

    /**
     * Deletes the calendar and the sessions that live in it. Their training records survive,
     * marked orphaned, so recorded hours never shrink. Google is not called: the Google
     * calendar and its events are not ours to destroy.
     */
    fun delete(id: UUID)
```

In `TrainingCalendarServiceImpl` — inject `private val trainingEventPersistence: TrainingEventPersistence` and `private val appUserService: AppUserService`:

```kotlin
    override fun sessionCount(id: UUID): Long = trainingEventRepository.countByCalendarId(id)

    @Transactional
    override fun delete(id: UUID) {
        val calendar = trainingCalendarRepository.findById(id).orElseThrow {
            IllegalArgumentException("Training calendar with id $id not found")
        }
        if (calendar.isDefault && trainingCalendarRepository.count() > 1) {
            throw IllegalStateException("Make another calendar the default before deleting this one.")
        }
        val actor = appUserService.getCurrentUser()
        // Through the persistence bean, not the repository: that is where TrainingRecordWriter
        // is wired, so each session's history is orphaned rather than erased.
        trainingEventRepository.findAllByCalendarId(id).forEach { event ->
            trainingEventPersistence.remove(event, actor)
        }
        trainingCalendarRepository.delete(calendar)
    }
```

- [ ] **Step 5: Run them to verify they pass**

Run: `./gradlew test --tests "*TrainingCalendarServiceTest*"`
Expected: PASS.

- [ ] **Step 6: Write the integration test that history survives**

Create `TrainingCalendarDeleteIntegrationTest.kt` — `@SpringBootTest` + `@Testcontainers` + `@MockBean GoogleCalendarClient`, wired like the existing `TrainingCalendarBootstrapIntegrationTest`:

```kotlin
    @Test
    fun `deleting a calendar orphans its training records instead of erasing the hours`() {
        val calendar = trainingCalendarService.add(
            TrainingCalendarRequest("delete-me@group.calendar.google.com", "Delete me"),
            enabled = true
        )
        val event = trainingEventRepository.save(TrainingEvent().apply {
            title = "Attended session"
            startTime = LocalDateTime.now().minusDays(1)
            endTime = LocalDateTime.now().minusDays(1).plusHours(1)
            attendanceStatus = AttendanceStatus.ATTENDED
            createdBy = user
            this.calendar = calendar
        })
        trainingRecordWriter.sync(event)
        val recordId = trainingRecordRepository.findByTrainingEventId(event.id!!)!!.id!!

        trainingCalendarService.delete(calendar.id!!)

        assertNull(trainingEventRepository.findById(event.id!!).orElse(null))
        val record = trainingRecordRepository.findById(recordId).orElse(null)
        assertNotNull(record, "the record must survive its calendar")
        assertNotNull(record.orphanedAt, "and be marked orphaned")
        assertEquals(60, record.durationMinutes, "with its hours intact")
    }
```

Match `trainingCalendarService.add`'s real signature — #58 gave it an `enabled` parameter; if the signature differs, call it as it actually is.

- [ ] **Step 7: Run it to verify it passes**

Run: `./gradlew test --tests "*TrainingCalendarDeleteIntegrationTest*"`
Expected: PASS.

- [ ] **Step 8: Add the confirm dialog and endpoint**

In `AdminCalendarController`:

```kotlin
    @GetMapping("/{id}/delete/confirm")
    fun confirmDelete(@PathVariable id: UUID, model: Model): String {
        val calendar = trainingCalendarService.findById(id)
            ?: return "fragments/confirm :: confirmDismissed"
        val count = trainingCalendarService.sessionCount(id)
        model.addAttribute("title", "Delete ${calendar.displayName}?")
        model.addAttribute(
            "message",
            "$count session(s) in this calendar will be removed from DanceBook. Their training " +
                "history is kept, so your recorded hours do not change. The Google calendar and " +
                "its events are left untouched."
        )
        model.addAttribute("confirmLabel", "Delete calendar")
        model.addAttribute("confirmAttrs", "/admin/calendars/$id/delete")
        return "fragments/confirm :: confirmDialog(${'$'}{title}, ${'$'}{message}, ${'$'}{confirmLabel}, ${'$'}{confirmAttrs})"
    }

    @PostMapping("/{id}/delete")
    fun deleteCalendar(@PathVariable id: UUID, model: Model): String {
        try {
            trainingCalendarService.delete(id)
        } catch (e: IllegalArgumentException) {
            model.addAttribute("calendarError", e.message)
        } catch (e: IllegalStateException) {
            model.addAttribute("calendarError", e.message)
        }
        model.addAttribute("calendars", trainingCalendarService.findAll())
        return "admin/dashboard :: calendarsSection"
    }
```

If the parameterised-fragment return string proves awkward, render a thin wrapper fragment in `admin/dashboard.html` that calls `confirmDialog` with those four model attributes and return that instead — the shared fragment must stay caller-agnostic either way.

- [ ] **Step 9: Add the Delete button**

In the `calendarRow` fragment, beside Edit and Verify:

```html
<button type="button" class="btn-outline btn-sm text-danger"
        th:hx-get="@{/admin/calendars/{id}/delete/confirm(id=${cal.id})}"
        hx-target="#confirm-dialog-host" hx-swap="innerHTML">
    Delete
</button>
```

- [ ] **Step 10: Full build and commit**

```bash
./gradlew build
git add src/main/kotlin/com/jankowski/rafal/dancebook/service/ \
        src/main/kotlin/com/jankowski/rafal/dancebook/repository/TrainingEventRepository.kt \
        src/main/kotlin/com/jankowski/rafal/dancebook/controller/web/AdminCalendarController.kt \
        src/main/resources/templates/admin/dashboard.html \
        src/test/kotlin/com/jankowski/rafal/dancebook/
git commit -m "feat: delete a training calendar and keep its history"
```

---

## Manual verification

```bash
docker compose up -d postgres
./gradlew bootRun
```

1. Rename a calendar inline; confirm the new name shows immediately and the Google ID is unchanged.
2. Try to change the Google ID of a calendar that owns sessions; confirm the input is disabled and the reason is shown.
3. Change the Google ID of a calendar with no sessions to something invalid; confirm it saves, the calendar is disabled, and the failure reason appears.
4. Delete a calendar with sessions; confirm the dialog states the count, that history is kept and that Google is untouched. After confirming, check `/training-events/history` still shows those sessions, marked orphaned, and that the statistics totals have not dropped.
5. Confirm the Google calendar and its events still exist in Google's own UI.
6. Try to delete the default calendar while another exists; confirm it is refused with the message naming the fix.
