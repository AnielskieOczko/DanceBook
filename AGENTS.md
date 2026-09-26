# AGENTS.md

Canonical onboarding for every coding agent working in this repository — Claude Code,
Antigravity (`agy`), and anything else. `CLAUDE.md` and `GEMINI.md` are pointers to this
file. **Put shared project knowledge here**, never in the pointers, so the copies cannot
drift apart.

Keep responses concise and focused on the task at hand.

## Commands

```bash
./gradlew build                 # compile + test + build Tailwind CSS
./gradlew bootRun               # run the app on :8080
./gradlew test                  # all tests
./gradlew test --tests "com.jankowski.rafal.dancebook.service.DanceFigureServiceTest"
./gradlew test --tests "*DanceFigureServiceTest.should create figure*"
./gradlew buildTailwind         # regenerate static/css/output.css only
./gradlew watchTailwind         # rebuild output.css on every source change, until interrupted
docker compose up -d postgres   # local Postgres (dancebook/dancebook @ :5432)
docker compose up -d sonarqube  # local SonarQube on :9000, for ./gradlew sonar
```

The app reads all config from env vars with no defaults — it will not boot without
`DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `GOOGLE_CLIENT_ID`,
`GOOGLE_CLIENT_SECRET`, `GOOGLE_REFRESH_TOKEN`, `GOOGLE_DRIVE_FOLDER_ID`.
LLM keys (`OPENROUTER_API_KEY`, `GOOGLE_AI_API_KEY`, `OLLAMA_API_KEY`) default to empty.

One-off data pipeline tasks (see `scripts/`): `./gradlew processFigures`,
`./gradlew generateFiguresSql`, `./gradlew generateReconciliationReport`.
These resolve paths like `docs/figures steps/AGENT_PROMPT.md` relative to the working
directory, so run them from the repo root.

## Stack

Kotlin 1.9 / Java 21 · Spring Boot 3.5 (Web MVC, Data JPA, Security, Thymeleaf) ·
PostgreSQL + Flyway · Thymeleaf + HTMX 2 + Tailwind 4 · Gradle Kotlin DSL.
Deployed to Google Cloud Run via `.github/workflows/deploy.yml`.

## Architecture

Layered, package-by-type under `com.jankowski.rafal.dancebook`:

```
controller/web/  @Controller   → Thymeleaf views
controller/api/  @RestController /api/... → JSON
        ↓
service/         interface + <Name>ServiceImpl pair for every service
        ↓
repository/      Spring Data JPA (+ *Specification.kt for dynamic filtering)
        ↓
model/           JPA entities (mutable `var`, nullable ids, allOpen via kotlin-jpa)
dto/             request/response records; dto/Mappers.kt holds entity↔DTO mapping
```

### The two "figure" concepts

This trips people up constantly:

- **`DanceFigure`** — a catalog entry in the syllabus (name, dance type, dance class,
  starting/ending feet and positions, preceding/following figure names, notes).
- **`Figure`** — a *timestamped occurrence* of a `DanceFigure` inside a `Material`
  (a video), carrying only `startTime`/`endTime`; its `name` delegates to the linked
  `DanceFigure`.

A `DanceFigure` owns `DanceFigureStepSet`s (named variants, one flagged `isDefault`),
each owning `DanceFigureStep`s tagged with a `role` of `"LEADER"` or `"FOLLOWER"`.
`DanceFigure.steps` is a convenience getter returning the default set's steps.

Other aggregates: `Material` (video + figures + comments, owned, private by default),
`CustomList` (a saved filter over notes, owned), `Choreography` → ordered
`ChoreographyEntry` list (owned), `AppUser`. Who can see each of them is decided in one
place — see *Access control* below.

### Domain events → activity feed

Services publish subclasses of the sealed `model/DomainEvent.kt` through
`ApplicationEventPublisher`. `service/ActivityEventListener.kt` consumes them with
`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` and persists
`ActivityEvent` rows, which drive the notifications page and navbar unread badge.
**When you add a mutating service method, publish the matching event** — otherwise the
change is invisible in the activity feed.

### Thymeleaf fragment catalog

`templates/fragments/` is the component vocabulary, and since #98 there is **one source per
component**: `icon`, `button` (`linkButton`, `actionButton`, `iconButton`, `submitRow`),
`form` (`field`, `selectField`, `textareaField`, `checkbox`, `toggle`, `richText`,
`errorSummary`, `rawField`), `page` (`pageHeader`, `sectionTitle`, `viewSwitcher`), `card`, `table`
(`dataTable`, `stepTable`), `badge`, `empty`, `alert`, `modal` and `rich-text`. Do not
hand-roll a page header, a badge or an empty state in a template — the fragment exists, and
the reason #98 was worth doing is that six training pages had each copied the header instead.

**A catalog fragment nothing calls is not a component, it is a second copy waiting to drift.**
`attendanceBadge` (in `badge.html`) and `statCard` (in `card.html`) both existed before #129 and
almost nothing called them, so the markup they were written to replace stayed spread across the
training views and drifted apart: the same session rendered unconfirmed in the accent on two
screens and in warning on a third, status labels were the raw enum in capitals on three screens
and sentence case on the fourth, and one copy carried a `CANCELLED` branch that could not fire
while another had no `CANCELLED` branch at all. `attendanceBadge` now takes a status and an
unconfirmed flag rather than an event, which is what lets the training history table pass its
narrower `TrainingOutcome` to the same fragment — before #130 it could not, so it showed no
unconfirmed state at all. Unconfirmed is `badge-warning` everywhere, per the colour table in #46
that reserves warning for exactly this and the accent for affirmative states. `statCard` gained a
caption, because the stat tiles carry a third line — the streak's basis, the skipped/upcoming
split — that the fragment could not express and that its `trend` parameter does not substitute
for. Those captions are content: a migration that dropped them would have passed the build and
made the page worse.

**No fragment may be named after an HTML element.** `~{template :: name}` is not a
fragment-name lookup, it is a **markup selector**, and a bare word matches any element
carrying that `th:fragment` *or any element whose tag name is that word*. A fragment called
`textarea` therefore resolves to itself plus every literal `<textarea>` elsewhere in the same
file, and Thymeleaf renders all of them. That is silent data corruption rather than a
rendering glitch: the browser posts one value per element, Spring joins the multi-valued
parameter with commas, and the saved value grows on every save — one material's description
reached `Test note…,Test note…,ohiopp` in the running app before #121. Five fragments carried
element names and were renamed for it: `select` and `textarea` became `selectField` and
`textareaField`, `button` and `link` became `actionButton` and `linkButton`, and `header`
became `pageHeader`. `FragmentNameAmbiguityTest` scans `templates/fragments/` against the full
HTML element list, which is what makes this a rule rather than something every caller has to
remember.

**Fragments declare no parameter signature and are called with named parameters.**
Thymeleaf validates arity strictly only when a fragment declares its parameters, so the old
six-parameter `page-header(...)` forced every caller to pass all six —
`notifications/history.html` passed dummy values purely to satisfy it. Signature-less plus
named parameters means a caller passes only what it needs and an absent parameter arrives
as `null`, defaulted inside the fragment.

The cost of that is real and worth stating: **a mistyped parameter name is silently `null`,
not an error.** `FragmentCatalogRenderingTest` is what makes it loud — it renders every
fragment twice, once with required parameters only and once with all of them, and fails on
a literal `null` or an unparsed `th:` attribute in the output. A new fragment, or a new
parameter on an existing one, belongs in that test's list or it is unguarded.

**A header with more than one action passes its toolbar as a slot**, not as a parameter per
button: the caller declares a named fragment in its own file and hands it over as
`~{::thatFragment}`, which the header inserts. That is what a parameter list cannot express
and what the six hand-copied headers existed to work around.

**Form fields bind through preprocessing**: `th:field="*{__${path}__}"`. `th:attr` cannot do
this — it writes plain output attributes after the dialect has already run. Because fragment
insertion is inline, the `*{…}` resolves against the caller's `<form th:object>`. Every field
fragment also carries an unbound branch — a plain named input, selected by `bound=false` or
`raw=true`, alongside a separate `rawField` — for a form with no `th:object` behind it. Since
#107 no template takes it: all eight form screens are bound. `FragmentCatalogRenderingTest`
still renders it, so it works, but no real page exercises it. See *Forms and validation* below
for the shape the bound ones use.

**Variants are semantics, never class fragments.** A fragment takes `variant='danger'` and
maps it to a class literal internally; it never concatenates `'badge-' + variant`, because
an assembled name appears in no scanned source, so Tailwind drops the rule while the
attribute still renders — an unstyled element with no error anywhere.

**A conditional and a fragment inclusion on the same element do not work together.**
`th:replace` is processed at attribute precedence 100 and `th:if` at 300, so the replacement
happens first and carries the host element — condition included — out of the document before
the condition is ever evaluated. The include is unconditional. Put the `th:if` on an enclosing
`<th:block>` instead. This is invisible at runtime, survives a green build and reads as
obviously correct, which is how it reached `page.html`, `badge.html` and `card.html` in #98
and went unnoticed: in all three the conditional include is an icon, and an icon with no name
renders an empty span with no glyph. By the time #125 fixed it the construct had been copied
into nine more templates, and there it wrapped error alerts and empty-state banners, where an
unconditional include is not invisible at all. Every occurrence now puts its condition on an
enclosing `<th:block>`, and `ThymeleafConditionalIncludeTest` fails the build on any
conditional (`th:if`, `th:unless`) sharing an element with any inclusion (`th:replace`,
`th:insert`, `th:include`), so the shape cannot return.

**Every icon goes through the `icon` fragment.** Since #101 no template renders a bare
`material-symbols-outlined` span and none carries an inline `<svg>`; a sweep of ~336 call
sites across 36 templates put them all behind `fragments/icon :: icon`. The size scale is
three steps and nothing else — `sm` 16px, `md` 20px (the default), `lg` 24px — so reach for
the nearest step rather than adding a fourth. Fill is `filled=true`, which applies
`.icon-filled`; the `th:style` and raw `style="font-variation-settings: 'FILL' 1"` spellings
that used to coexist with it are gone, and `grep -r 'font-variation-settings' templates`
should stay empty.

Extra classes go through **`cls`**, not `class`. Passing `class='text-primary'` is the
silent-`null` trap above in its most expensive form: it renders a valid page with the colour
quietly dropped, and it cost a full review round on #101 when 54 call sites had it — the
chevrons on collapsible sections had stopped rotating because their
`group-open:rotate-90` rode on that parameter. `FragmentCatalogRenderingTest` asserts a
passed class reaches the output and `HtmxFragmentRenderingTest` asserts a real template
still emits the rotation classes, so the pair fails if this recurs.

**An optional parameter you omit is not absent — it inherits from the caller's scope.**
Thymeleaf resolves an unpassed fragment parameter as an ordinary variable lookup, so it
finds whatever the enclosing template has in scope under that name. #137 shipped an icon
inside a `pageHeader` toolbar without a `title`, and every icon picked up the header's
`title='Training'` as a hover tooltip. Nothing looked wrong until the rendered HTML was
read. Inside any slot of another fragment, pass `title=null` explicitly, as the view
switcher does; `TrainingNavAndSwitcherTest` pins it there.

Icons built in JavaScript cannot use a Thymeleaf fragment, so they go through `renderIcon`
in `static/js/main.js`, which emits the same classes. `main.js` loads in `layout.html` for
every page, so call it directly — it is always defined. The one icon that is not a glyph is
the multi-colour Google brand mark, served from `static/images/google.svg`, because
substituting a Material Symbol for a brand mark would be wrong rather than merely
approximate.

### Forms and validation

Since #107 every ordinary field on the eight create/edit screens — Notes, Figures, Dance
styles, Categories, Collections, Choreographies, Training sessions and the profile password
form — renders through `fragments/form`, and all eight report failure the same way: the
offending control takes `border-error ring-1 ring-error` and shows its message underneath,
and `errorSummary` lists them at the top of the form. Hand-copied field markup is gone from
those templates, which got shorter by doing it.

**A form only shows errors if its controller is shaped for it.** The handler takes
`@Valid @ModelAttribute` *and* a `BindingResult` immediately after it, and on
`bindingResult.hasErrors()` re-populates whatever the view needs — dropdown options, the
entity id, the current image — then returns the view name instead of redirecting.
`CustomListWebController.kt` is the shape to copy. Omit the `BindingResult` and Spring throws
rather than binding: a full-page POST lands on the Whitelabel 400 page, and an htmx POST shows
the user *nothing at all* — htmx's default `responseHandling` does not swap a 4xx, so the
submit button simply does nothing and the only trace is an `htmx:responseError` in the console.
The silent one is the worse failure, and it is the one that was live on the admin user handlers
until #107, since those post over htmx.

**Request DTOs give every bound field a default.** `@ModelAttribute` binds through the
constructor, so a non-null Kotlin parameter with no default fails to construct when its field
is absent, and the request dies before validation runs — leaving no object to hang an error
on. `val name: String = ""` next to `@field:NotBlank` is what makes the error visible.

**A constraint on a non-null Kotlin type never fires.** `@field:NotNull var role: Role` cannot
fail, because the type cannot hold null. Declare it `Role?` and keep the `@NotNull` — and do
*not* reach for a default instead, which is the tempting fix and the wrong one:
`role: Role = Role.USER` turns a POST that omits `role` into a silent demotion of an admin
rather than a visible error. `MaterialRequest.version` stays required for the same reason —
defaulting it to `0` would trade away the optimistic-locking token.

**Select options are read by property name.** `optValue` and `optLabel` are strings the
fragment applies as `opt[...]`, so the option type has to expose those properties. That is why
`DanceClass` carries a `displayName`, and why `FormSelectOption` (`value`, `label`) exists at
all — for a select whose controller has no domain type to hand over.

Four things stay hand-written on purpose, and moving them into the catalog would break them:
the Note form's htmx-driven category select, the style select carrying `danceTypeOptions` (a
fragment `MaterialWebController` names in a string, so it must not move), the Drive upload
block, and the repeating rows JavaScript clones from indexed names on the training, figure and
link forms.

### Rich text

**Every long-form text field passes through `RichTextService`, and it is the only thing that
decides what markup may exist.** Since #114 it runs on write in all nine service sites —
material descriptions, comments, figure notes, session and series descriptions, choreography
descriptions — and again on read. Re-sanitising on read is what makes skipping a migration
honest: notes written before #114 are still plain text in the database, and nothing has to
backfill them.

`templates/fragments/rich-text.html` is **the only file in the repo permitted to contain
`th:utext`**, and `UnescapedTemplateOutputTest` fails the build if unescaped output appears
anywhere else. That test also asserts the fragment still uses it, so the guard cannot quietly
become a tautology. Its `excerpt` fragment renders with `th:text` over plain-text-stripped
input, which is why list and index views are structurally incapable of leaking markup rather
than merely careful not to.

The safelist allows bold, italic, bullet and numbered lists, and links — nothing else. It
centres on the block markup **Trix 2 actually emits**, which is `<div>`, not `<p>`; #119 put
Trix on the six long-form fields and needed no safelist change, which is what that choice
bought. A link gets
`rel="nofollow noopener noreferrer"` enforced, and anything that is not `http`, `https` or
`mailto` loses its `href`.

**The trap is emptiness.** An untouched rich text editor does not submit an empty string, it
submits markup — and `"<div><br></div>".isNotBlank()` is `true`. `clean()` therefore returns
`null` when the content has no visible text, and that is why the `?.takeIf { it.isNotBlank() }`
gates that used to guard these writes are gone: there were six in `TrainingSeriesServiceImpl`
alone, and leaving any one of them would render an empty description panel on a detail page.
**Decide emptiness by calling the service, never by testing the string yourself.**

Two consequences worth knowing before touching this. Session descriptions are converted with
`toPlainText()` before they reach Google Calendar, or raw markup shows up in calendar entries.
And length limits use `@RichTextLength`, which counts the text the user typed rather than the
bytes of markup, behind a much larger raw ceiling — a plain `@Size` on one of these fields will
reject a short note the moment formatting triples its byte count.

Note the two similar names. `fragments/rich-text.html` is the **display** fragment described
above; `richText` in `fragments/form.html` is the **input** field, and since #119 it renders a
Trix editor rather than the `<textarea>` it carried from #98.

### The rich text editor

**`richText` renders a hidden `<input>` with a `<trix-editor>` bound to it by id.** The hidden
input is what `th:field` owns and what submits, so the value still arrives as an ordinary form
field and nothing on the server knows an editor exists. One fragment covers both cases: the
bound one, and `raw=true` for the two comment forms. The editor element carries
`data-required="true"` rather than `required`, and the label points `for` at the editor, which
a click handler in `main.js` turns into focus because a custom element does not get that for
free.

**Trix loads per page, not from the layout.** Five templates carry
`<script src="https://unpkg.com/trix@2.1.19/dist/trix.umd.min.js">` near the bottom:
`materials/form.html`, `materials/view.html`, `choreographies/form.html`,
`dance-figures/form.html` and `training-events/form.html`. **A new page that calls the
`richText` fragment must add that tag itself**, or the field renders as an inert hidden input
with no visible control and nothing fails — not the build, not the page. No CSP edit is needed:
`script-src` already allows `unpkg.com`.

**The toolbar is pruned, not configured.** Trix ships more controls than the safelist permits,
so a `trix-initialize` listener in `static/js/main.js` removes strike, heading, quote, code, the
two nesting buttons and the file and history groups, leaving the five the safelist allows —
bold, italic, link, bullet list, numbered list. Pruning on the event rather than at render is
what makes an htmx-swapped editor come up with the same toolbar as a server-rendered one. A
`trix-file-accept` listener refuses attachments outright, since nothing downstream would store
them.

**Required-ness is enforced by hand, because constraint validation cannot see the value.** The
real input is hidden, and a hidden input is excluded from constraint validation — so `required`
on it is ignored, htmx's `checkValidity` waves the form through, and a blank note reaches a
server that drops it silently. A **capture-phase** `submit` listener on `document` runs ahead of
htmx's own form-level listener and calls `stopImmediatePropagation()`, so htmx never sees the
event; an `htmx:configRequest` guard covers requests not issued by a submit. Both decide
emptiness from the editor's text, never from the markup in the input — the same trap as above,
one layer up. That halt is only the first line of defence: since #124 the refusal is the
server's, and it is visible. `CommentController` hands `content` straight to `CommentService`,
whose `clean()` boundary already raised on a blank result, and that exception now becomes a
`commentError` on the model rendered through `fragments/alert` **inside the swapped region** —
`comment-list` for a post, `comment-edit-form` for an edit — so it reaches the page over htmx
instead of dying in a swap that never happens. Editing to blank re-renders the edit form with
the original note intact. Emptiness is still decided in exactly one place.

**The skin is ours and deliberately unlayered.** `frontend/input.css` styles `trix-toolbar` and
`trix-editor` in plain unlayered CSS alongside the FullCalendar block, so the token rules beat
Tailwind's layers, and the toolbar icons are masks tinted by `currentColor` rather than a
duplicated hex. The display half needed rules too: Tailwind's preflight strips list markers, so
a stored bullet list rendered as flat lines until `.rich-text` got its own.
`TailwindOutputCssTest` asserts both blocks survive into `output.css`, because a rendering test
asserts on HTML and cannot see a missing rule.

### Dialogs

**Every dialog is a native `<dialog>`, and nothing about opening or closing one is written
by hand.** Since #111 there are no `<div>` overlays: `fragments/confirm-dialog` (named from
16 controller sites), the three in `fragments/bulk-edit-dialog`, and the delete dialog in
`lists/view.html` are all real dialogs, and `fragments/modal.html` is the generic one to
call for a new case.

The mechanism is worth knowing before you add one. A controller returns the dialog fragment,
htmx swaps it into `#confirmModalContainer` in `layout.html`, and a single `htmx:afterSwap`
listener calls `showModal()` on whatever dialog just landed; a `close` listener empties the
container afterwards. That is the entire JavaScript. **Focus trapping, Escape, the inert
backdrop and the close button are the browser's**, so do not reimplement them — the ~60 lines
that used to do it by hand were deleted, and they never trapped focus anyway: Tab walked
behind the dialog into the page underneath. A close button is `<form method="dialog">`, which
needs no script at all.

**These four fragments are a standing exception to the frozen-root rule below.** #111 changed
their root tag from `<div>` to `<dialog>`, which that rule otherwise forbids. The part that
actually matters — the file, the fragment name and every `id` — is unchanged, because those
are what htmx targets and what a controller names in a string. `HtmxFragmentRenderingTest`
now pins each of these roots as a `<dialog>`, so the exception is recorded rather than left
open as precedent. Do not read it as permission to change another fragment's tag.

### Error pages and failed requests

**`templates/error/404.html`, `error/500.html` and `templates/error.html`** are the designed
error pages, resolved by Spring Boot's own convention — specific status first, then the
generic `error` view, which is why the fallback sits at the templates root rather than inside
`error/`. No controller is involved and none should be added.

**`SecurityConfig` permits the `ERROR` dispatch type, and that line is what makes them
render.** The authorization rules apply to error dispatches too, so without it an error is
re-authorized and the user is redirected to login instead of seeing the page. Deleting that
line does not break the build and does not fail a fragment test — it silently reverts the
feature.

**A signed-out visitor goes to the login page for every URL, whether or not it exists.** That
is deliberate and was reverted into place after #111 briefly changed it. Sending unknown URLs
around the authentication check leaks which routes exist — a real one redirects to login, an
invented one 404s — and the handler lookup it requires cannot see resource handlers, so
static paths break unless each is special-cased. The designed 404 is for signed-in users,
which is everyone who actually browses the app. `ErrorPageIntegrationTest` asserts the
redirect, so reintroducing the old behaviour fails the build.

Error pages expose no stack trace, no exception message and no class names, and the
`server.error.include-*` properties stay at their defaults in every profile.

**htmx does not swap a 4xx or 5xx response.** Its default `responseHandling` marks them
`swap: false, error: true`, and this app overrides neither that config nor the events, so
before #111 a failed background action did *nothing at all*: the button went dead, no message
appeared, and the only trace was a console error. `htmx:responseError` and `htmx:sendError`
listeners in `main.js` now put a message in `#alert-container` through `showErrorAlert`.

`showErrorAlert` builds the alert markup as a JavaScript string, duplicating
`fragments/alert.html` on purpose — the same bargain `renderIcon` makes, for the same reason:
content built in JavaScript cannot call a Thymeleaf fragment, so it emits the same classes
instead, and it calls `renderIcon` for its own icons. **It interpolates its argument into
`innerHTML`, so pass it literal text** — never a server response body, an exception message
or anything a user can influence.

### Dense tables

A dense table keeps its own `overflow-x: auto` container at every width, **pins its identifying
column**, and names that container for assistive technology with `tabindex="0"`, `role="region"`
and an `aria-label`. The four admin tables have done this since #131. One pattern at every width,
and the same mechanism the step tables already used.

#46 originally prescribed the opposite below 1024px — a stacked definition list — and #131 built
four competing versions of the worst-case table and measured them before dropping it. The stacked
list cost 2.6x the height and repeated a label per field where the value spoke for itself, but the
decisive objection was structural: `calendarRow` is a `<tr>` fragment whose edit control swaps with
`hx-target="closest tr"`. A stacked layout has no `tr`, and the controller has no way to know which
layout to return, so it would have cost a second row fragment plus a second edit-form variant per
table. Nothing was hidden and the body never scrolled sideways either, so the prescription was
answering a question the screen had not asked. The real defect was that scrolling right to reach
the action buttons took the identifying column off screen, leaving you able to press Delete on a
row you could no longer name.

**The pinned cell inherits its background and must never set one.** Cells paint after rows in CSS
table order, so an opaque background on the pinned cell covers whatever the row is painting — the
hover highlight, and the danger tint marking an orphaned Drive file, on precisely the column that
names the file. `table-row` therefore paints an opaque base and `table-cell-pinned` carries
`background-color: inherit`, which tracks every row state, including ones added later, without the
stylesheet enumerating any of them or naming a template's utility class. The first attempt at this
enumerated the states and hardcoded `.bg-danger-soft\/50` into `input.css`;
`PinnedColumnRowStateGuardTest` now fails the build on either shape.

**`table-row` shares its name with a stock Tailwind display utility.** Tailwind emits
`.table-row{display:table-row}` and the custom `@utility` emits the border and background, so both
rules exist, and `md:table-row` in `dance-figures/form.html` picks up the custom one too. Harmless
today — those rows already carry `bg-white`, and `--color-surface` and `--color-background` are
both `#ffffff`, so the opaque base is inert outside the admin screen — but it means an edit to
`table-row` reaches further than the tables it was written for. The new row background also
competes with the orphan tint at equal specificity, where cascade order decides: `.table-row` is
emitted before `.bg-danger-soft\/50`, so the tint wins.

**The figures catalog is the one dense table that is not the screen at every width** (#132).
List mode renders both of its forms in the same `figuresTable` fragment — the cards under
`lg:hidden`, the table under `hidden lg:block` — and CSS picks one at 1024px. That is the answer
to the objection that sank the stacked list in #131: the controller cannot know the viewport, so
one response has to carry every width. It is affordable here and was not there because these rows
have no row-level swap — filters, search and sort replace the whole fragment — so there is no
`closest tr` to lose. Grid mode stays cards at every width, and the list/grid toggle keeps its
two options; the table is what "list" means when there is width for one, not a third mode.

Three rules the table established, for the next one that needs them:

- **A sortable header drives the existing sort control; it does not send its own request.** Each
  `js-sort-header` button carries the `sortBy` it would select, and the delegated handler in
  `static/js/main.js` writes that into `#filterSortBy` and fires its `change`. The select stays the
  only source of sort state, so the header and the control cannot disagree, and the server renders
  `aria-sort` and the next toggle direction from `selectedSortBy`. Giving the header its own
  `hx-get` would send `sortBy` twice whenever the form is included.
- **Row actions are inline controls, never the ⋮ dropdown.** The dropdown is absolutely
  positioned, and the table's `overflow-x` region clips it. Inline buttons also match the admin
  tables.
- **A per-row derived value is one query for the page, not one per row.** The Steps column reads a
  set from `DanceFigureService.findFigureIdsWithSteps(ids)`, which uses the same "has a step set"
  definition as the Steps Syllabus filter, so the column and the filter cannot disagree either.

The pinned Name cell wraps within `min-w-[200px] max-w-[20rem]` rather than `whitespace-nowrap`:
catalog names run to 102 characters, and an unwrapped one made the pinned column most of the
region at 1024px, leaving the columns it exists to keep in view nowhere to scroll.

### HTMX partial rendering

Web controllers accept `@RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?`
and return either a full view name or a fragment selector:

```kotlin
return if (isHtmxRequest == true) "dance-figures/list :: figuresTable" else "dance-figures/list"
```

They also skip loading filter-dropdown data on HTMX requests. Follow this shape for new
list/filter endpoints (`controller/web/DanceFigureWebController.kt` is the reference).

**A fragment a controller names in a string is frozen.** The selector is a string, so a
rename is a runtime 500 the compiler cannot see, and the root element's `id` is what the
swap targets — drop it and the first swap works while every later one silently no-ops.
When refactoring a template that contains one, migrate its *contents* and leave the
`th:fragment` marker on its original element, in its original file, with its original tag
and `id`. `HtmxFragmentRenderingTest` (#98) turns this into a build failure: it requests
seven htmx endpoints with `HX-Request: true` and asserts each still returns its expected
root id.

### Global template model

`controller/web/NavbarAdvice.kt` is a `@ControllerAdvice` supplying `currentUser`,
`navLists`, `unreadNotificationCount`, `pollInterval`, `autoLogout`, and `activeNav` to
every template. Adding a new top-level route means adding a branch to `activeNav()`
for the navbar to highlight correctly.

### Navigation

Since #137 the nav has **six items, identical on desktop and mobile, in the same order**:
Dashboard, Notes, Figures, Collections, Choreographies, Training. Profile is reached
through the avatar, and admin through the gear icon; neither is a nav item. A seventh item
is a design decision for #46, not something to add alongside a feature. The mobile bar
has no room for one.

**Training is one section with five views, not five routes.** List, Calendar, Timeline,
Stats and History each have their own URL, but `activeNav()` maps all of
`/training-events/**` to `training-events`. Every one of them is titled "Training" and
renders `fragments/page :: viewSwitcher(currentView=…)` in its header toolbar, where the
switcher names the view and marks it with `aria-current="page"`. A new training view is a
switcher entry, not a nav item or an `activeNav()` branch. Before #137 the views
cross-linked through hand-copied button rows that had drifted apart (History had no
Calendar link, and Calendar had no History link), and Timeline held a nav slot of its own.
The switcher is neutral by design. Its `view-switcher*` utilities use surface tokens, not
the accent, so it never competes with the page's one primary action.

**Mobile labels must fit a sixth of 375px, about 61px each.** Each item is `flex-1`,
which is what keeps the six evenly spaced. The labels are sentence case at 11px. The
tracked-out uppercase they used to carry was retired in #93, and it does not fit:
"COLLECTIONS" measured 84px. "Choreographies" does not fit at any legible size (73px at
10px), so on mobile it shows **"Choreos"** with `aria-label="Choreographies"` on the link.
That is the one deliberate label difference between the two navs. Check a label change by
measuring it in a 375px viewport; a MockMvc test cannot see overflow, and #137's first
pass claimed a fit that the browser showed was false.

### Who the current user is

**Resolve the current user through `AppUserService.getCurrentUser()`, and never through
`@AuthenticationPrincipal`.** Login is form-based *or* Google OAuth2, and the two put different
principal types in the security context: a `UserDetails` for form login, a `DefaultOAuth2User`
for Google. `getCurrentUser()` reads the context itself and handles both — an OAuth2 principal
is looked up by its `email` attribute, a form principal by `authentication.name`. A parameter
declared `@AuthenticationPrincipal userDetails: UserDetails` simply binds `null` under Google
login, and Kotlin's non-null check then throws `NullPointerException` *before the method body
runs*, which is why the failure is a stack trace rather than a friendly error. That was #122:
`CommentController` was the only place in the codebase still taking the user that way, and
posting, editing and deleting a note were all broken for every Google user. No `@AuthenticationPrincipal` remains in the codebase.

**In templates the same split breaks identity comparisons.** `#authentication.name` is the
username under form login and the *email* under OAuth2, and usernames here are not emails — so
`#authentication.name == c.author.username` is false for the author of the row whenever they
signed in with Google, which quietly hid the Edit and Delete controls on their own comments.
Compare the `currentUser` that `NavbarAdvice` supplies, by id:
`${currentUser != null and currentUser.id == c.author.id}`. No `#authentication` remains in the
templates either.

**Where there may be nobody signed in, use `getCurrentUserOrNull()`.** It returns `null` for
an anonymous or missing authentication and lets every other exception through.
`getCurrentUser()` throws `EntityNotFoundException` in that case, which the global handler
turns into a 404. Do not wrap either in `catch (e: Exception) { null }`: #151's first round did
that nine times, which quietly turned a database failure into "anonymous, public items only".

### Access control: who can see an item

Since #151, **notes, collections and choreographies are owned and private by default.** Each
carries an `owner` and a `visibility` (`model/Visibility.kt`: `PRIVATE` or `PUBLIC`); the old
`is_public` boolean is gone. Comments have no visibility of their own — they are visible
exactly when their note is. Figures, dance styles and categories have no visibility and are
seen by everyone — figures are also *edited* by everyone (see *Figures are community-edited*
below).

**There is one access rule, and it is a `Specification`.** `MaterialSpecification`,
`CustomListSpecification` and `ChoreographySpecification` each expose `visibleTo(user)`:
the user owns the item, *or* it is `PUBLIC`, *or* a row in the `share` table grants it to them.
Admins see everything. `CommentSpecification.visibleTo` joins to the note and reuses the note's
rule, and `ActivityEventSpecification.visibleTo` reuses the note and collection rules inside
subqueries. **Do not write a second copy** — an in-memory `isVisibleTo`, a JPQL `OR isPublic`
query, a template check. The first round of #151 had the rule in three places, and the
repository copies had already lost the share and admin branches before anything had shipped.

**Every lookup that serves a user goes through it, `findById` included.** The service's
`findById` is `findOne(visibleTo(user).and(byId(id)))`, so a hidden item and a missing item
are the same `EntityNotFoundException`. Anything that loads an item by id must go through the
service, never the repository: `CommentServiceImpl` used to load the note straight from
`MaterialRepository`, which let anyone comment on a note they could not see. The only
deliberately unfiltered queries are system housekeeping — `findAllDriveFileIds` for the
storage cleanup job and the admin storage page.

**Hidden is 404; visible-but-not-yours is 403.** `GlobalNotFoundExceptionHandler` maps
`EntityNotFoundException` to a 404 on pages, htmx fragments and `/api/**`. Before it existed a
missing id produced a 500. Changing an item you can see but do not own — another user's public
note, collection or choreography — throws `AccessDeniedException` from the service's
`checkOwnership`, which is a 403. Only the owner or an admin edits, deletes or changes an
item's visibility; everyone who can see a note can comment on it. The edit *forms* of an item
you do not own return 404, and templates hide the edit actions by comparing `currentUser.id`
with the owner's id (see *Who the current user is*).

**Collections are saved filters, not lists of notes.** A `CustomList` stores a name filter, a
minimum rating and dance types or categories; its notes are computed when it is viewed, through
the *viewer's* `visibleTo`. So a note made private drops out of everyone else's view of every
collection with no extra code, and there is nothing to count as "N private items". The owner of
a public collection sees a warning when some of *their own* private notes match its filter.
Choreographies contain only figures and section labels, which are always public.

**The activity feed is filtered in the query, not after loading.** The navbar badge is a
`COUNT` and the history page is paginated, so filtering loaded rows would make both disagree
with what is shown. Each `ActivityEvent` also stores `target_visibility`: once a note is
deleted there is nothing left to join to, and the entry is shown to other users only if the
note was public when it was deleted. Feed entries about figures and training sessions are not
filtered — per-user calendars are #154 and #155.

**A new entity that users own gets the same shape:** `owner` + `visibility`, a
`<Entity>Specification.visibleTo`/`byId` pair that reads the `share` table under its own
`item_type` string (`MATERIAL`, `CUSTOM_LIST` and `CHOREOGRAPHY` are taken), `findById` through
them, `checkOwnership` throwing `AccessDeniedException`, and a second-user test
(`controller/web/AccessControlSecondUserIntegrationTest.kt`) proving user B gets a 404 until
the item is public. Nothing writes to `share` yet; the rule reads it so sharing with a person
later means adding rows and a UI, not changing the rule.

**Drive videos are not covered by this.** A private note's video is hidden only because the
note page that carries its file id returns 404; the files themselves are readable by anyone
with the link, and `/api/materials/upload-config` hands out the app's Drive token. That is
#158.

### Figures are community-edited

Since #152 a `DanceFigure` works like a wiki page. It is always public, and **any user can edit
any figure, syllabus figures included**. The reason is that figures are the shared vocabulary
that notes and choreographies are built from: a private figure would let one user break
another user's choreography by hiding a figure it uses. So there is no `visibility`, no
`visibleTo`, and no `checkOwnership` on edit. Do not add them by analogy with notes.

- **`createdBy`** is recorded on create. V33 backfilled it from the earliest
  `DANCE_FIGURE_CREATED` activity event. Syllabus imports, and older figures with no such
  event, stay null.
- **Syllabus figures are recognised by `predefined`**, which `SyllabusImporterService` sets,
  and never by `createdBy == null`.
- **Concurrent edits use optimistic locking.** The entity has a `@Version` and the edit form
  posts it as a hidden field. The service compares the posted token before saving, the way
  `MaterialServiceImpl` does. A stale form, or a lost race at commit
  (`ObjectOptimisticLockingFailureException`), becomes the form-level error
  `DanceFigureService.CONFLICT_MESSAGE` rather than an overwrite. `DanceFigureRequest.version`
  is nullable only because the create and inline-create paths have no token. An update with a
  null version is a conflict, which is correct.
- **Deleting has three rules**, and they live in two places:
  - `DanceFigure.isDeletableBy(user)` covers who may delete: the creator or an admin, and only
    an admin for a syllabus figure. Figures whose creator is null can therefore only be deleted
    by an admin. Templates call the same method to decide whether to show a delete button, so
    the rule has one copy.
  - The service refuses when **another user's** notes or choreographies use the figure, and
    it counts private ones too. It throws `FigureInUseException`, which the controller turns
    into a `deleteError` flash on the figure page. The message gives counts only and never
    names or links the items, because the deleter may not be allowed to see them.
  - A delete that breaks the permission rules is an `AccessDeniedException` (403).
- **The deleter's own items keep the database's behaviour.** A note's pins of the figure
  cascade away, and a choreography entry keeps its place with `dance_figure_id` set to null.
- `CommunityFiguresIntegrationTest` is the second-user test for all of this.

**A slice test that needs `currentUser` passes it as a flash attribute.** The `@WebMvcTest`
template tests run with security off, and signing someone in breaks the layout's
`sec:authorize` ("No visible SecurityExpressionHandler"). Spring skips a `@ModelAttribute`
method when the model already holds that attribute, so
`.flashAttr("currentUser", viewer)` supplies the viewer without touching `NavbarAdvice`.
`DanceFigureDenseTableTemplateTest` does this.

### Attendance is per user

Since #153 a training session has **no attendance of its own**. Each person's status lives in
the `attendance` table, one row per `(training_event_id, user_id)` (`model/Attendance.kt`,
mapped as `TrainingEvent.attendances`). The `training_event.attendance_status` column is gone.
This groundwork lets two people share a session once calendars are shared (#154, #155).

- **Always ask for a specific user.** Read with `event.attendanceFor(user)` and
  `event.isAwaitingConfirmationFor(user)`, and write with `event.setAttendance(user, status)`.
  All three take a non-null `AppUser` and match by id. **A missing row means `PLANNED`**, so a
  new attendee never needs a backfill. There is deliberately no user-less accessor. The first
  round of #153 had one that fell back to `createdBy`, and it would have shown one person
  another person's status without any error. Templates pass `currentUser` (see *Who the
  current user is*). `TrainingEventPalette.swatchFor(event, user)` takes the user for the same
  reason.
- **The unconfirmed rule still has two copies.** `isAwaitingConfirmationFor` has a SQL mirror
  in `TrainingEventSpecification`. That mirror left-joins the attendance row for the user whose
  sessions are listed and treats a missing row as `PLANNED`. A change to one needs the same
  change to the other.
- **`training_record` is unique on `(training_event_id, created_by_id)`**, and `created_by` is
  the attendee, not the session's creator. `TrainingRecordWriter.sync(event, user, status)`
  writes, updates or removes one attendee's record, and nothing can call it without saying
  whose record it is. `syncEventDetails(event)` copies title, time, duration, calendar and
  styles onto every attendee's non-orphaned record when the session changes, and never touches
  their outcomes. `orphan` stamps every attendee's record when a session is deleted.
  `TrainingRecordRepository` has no single-record lookup by session, because more than one
  record can now exist per session.
- **Who can reach a session has not changed.** Lists, the calendar range, the timeline and
  stats are still scoped to sessions the current user created. Only the owner or an admin can
  record attendance, singly or in bulk, and the write always goes to the *acting* user's row.
  As a result, an admin marking someone else's session records the admin's own attendance, not
  the owner's. The first round of #153 had widened lists to "created by me, or I have an
  attendance row" and dropped the ownership check, which let anyone pull any session into
  their own views. Member access belongs to #155, through calendar membership, not attendance
  rows.
- **Google Calendar sync never reads or writes attendance.** `CalendarReconciler` creates
  sessions without a status, and a synced update or delete reaches history only through
  `syncEventDetails` and `orphan`.
- **Loading:** `attendances` is `EAGER` with `@BatchSize(50)`. `TrainingEventRepository.findById`
  overrides the default with an entity graph that loads `calendar` and `attendances`. The
  single-session paths (delete, reschedule, the detail page) use the calendar outside a
  transaction, while `calendar` itself stays `LAZY` for lists.
- **V34** moved every existing status to the session's `created_by`, and records already
  belonged to them, so no existing user's numbers changed. `TrainingAttendanceMigrationTest`
  pins the backfill, and `TrainingAttendancePerUserIntegrationTest` covers two users on one
  session: separate outcomes, stats and history, a refused non-owner, and both records orphaned
  on delete.

### LLM providers

`service/LlmProvider.kt` defines a provider interface (`openrouter`, `google-ai`,
`ollama`); `LlmProviderRouter` resolves one by name from the injected list. Add a
provider by implementing the interface and registering it as a `@Service` — the router
picks it up automatically. `GuidedFigureParseService` uses this to parse syllabus pages
into `DanceFigureRequest`s; `SyllabusImporterService` does the bulk dataset import.

## Constraints and gotchas

- **Schema changes require a Flyway migration.** `spring.jpa.hibernate.ddl-auto=validate`,
  so an entity field with no matching column fails at startup. Add
  `src/main/resources/db/migration/V<next>__description.sql` — check that directory for the
  highest version rather than trusting a number written here, which goes stale every release.
  `config/FlywayConfig.kt` runs `repair()` before `migrate()` on every boot.
- **Never put the word `new` inside a `${...}` in a fragment expression.** Thymeleaf
  evaluates fragment expressions — the `~{...}` of `th:replace`, `th:insert` and
  `th:include` — in *restricted mode*, which forbids object instantiation, static class
  access and request-parameter access. The check matches the SpEL **text**, not the parsed
  syntax tree, so it cannot tell the `new` operator from the English word: a parameter
  reading `${cond ? 'Add a new material' : 'Edit'}` throws `TemplateProcessingException`
  blaming object instantiation. The match is lowercase `new` followed by whitespace, and
  only inside a `${...}` — a plain quoted literal never reaches the SpEL evaluator, so
  `'/materials/new'`, `'New Material'` and `'renewal'` are all fine. Build the string in a
  `th:with` on an enclosing element, which is evaluated unrestricted, and pass the variable
  in. This broke the Add material page for two releases (#103), because the template parses,
  the application starts and the build passes — it only fails when the page is requested.
  `ThymeleafRestrictedExpressionTest` scans every template for the pattern.
- **Tailwind scans templates, `static/js`, and `src/main/kotlin`.** There is no
  `tailwind.config.js` and no safelist: `frontend/input.css` pins its own sources with
  `@import 'tailwindcss' source(none)` plus three `@source` globs. A class reaches the
  stylesheet only if it appears literally in one of those trees, so build class names as
  whole literals — `'badge-' + status` yields nothing. `buildTailwind` in
  `build.gradle.kts` declares the same three directories as task inputs; change one place
  and you must change the other, or Gradle serves stale CSS.
  `TailwindOutputCssTest` guards the classes that only exist in JS or Kotlin, and since
  #95 also that the utilities deleted there stay deleted, that the type tokens reach the
  output, and that control borders resolve through `outline`.
  `static/css/output.css` is generated and gitignored; never edit it by hand.
- **Colours, type and elevation come from the design tokens** in the `@theme` block of
  `frontend/input.css`. Tailwind 4 emits every entry as a CSS custom property on `:root`.
  The palette is deliberately small: five neutrals (`surface`, `surface-container`,
  `outline-variant`, `outline`, `on-surface-variant`, `on-surface`), one accent
  (`primary`), and two status colours (`error`, `warning`). Use the tokens, never raw
  Tailwind palette values. There is **no success green** — affirmative states use the
  accent, which is what keeps the accent consistently meaning "affirmative".
- **`outline` and `outline-variant` are not interchangeable.** `outline` (3.15:1 on white)
  carries form-control borders, where AA wants 3:1 for non-text contrast.
  `outline-variant` (1.29:1) is decorative hairlines and table rules only — never put it on
  a control.
- **A background token never goes in a text slot.** Text on a coloured ground takes the
  matching foreground token — `on-primary` over `primary`, `on-error` over `error` — never
  `surface`. `text-surface` renders the right pixel today only because the page ground is
  also white, which is exactly why the mistake survives review; it is the vocabulary
  collapse #46 exists to end.
- **Two type faces, by role.** Inter for UI, labels, navigation and all tabular data;
  Source Serif 4 for long-form prose only. Nothing else — Atkinson Hyperlegible and Manrope
  were retired in #93, and the tracked-out ALL-CAPS label style went with them.
- **Elevation has exactly two levels.** Level 0 is flat with a 1px `outline-variant`
  hairline — every card, table, input and panel — so the stock shadow tokens (`shadow-xs`,
  `-sm`, `-md`) resolve to `none`, and a flat surface stays flat in every state, hover
  included. Level 1 is `shadow-ambient`. #95 cut it back from ~30 call sites to the 12
  that genuinely float: the sticky header, the fixed mobile bottom nav, the modal and
  confirm dialogs, the two dropdown menus, the agenda's fixed bulk-action bar, and the
  calendar quick-create popover. Putting it on anything that sits in the page flow is the
  mistake it exists to prevent.
- **The component layer is the `@utility` blocks in `frontend/input.css`** — 70 of them,
  consumed heavily by the templates (`card`, `form-input`, `form-label`, `table-cell`,
  `badge`). #95 put every one on the canonical tokens, so nothing in the stylesheet reaches
  for a legacy alias any more, even though the aliases stay *defined* in `@theme` for the
  ~30 templates that still name them. Write a new utility in canonical tokens only.
  A `@utility` emits no CSS until a scanned source names it, so a dead one is invisible in
  the output and accumulates silently — #95 deleted 25. Search templates, `static/js` and
  `src/main/kotlin` before deleting or renaming one.
  Since #98 this layer has a counterpart in `templates/fragments/` (above): the `@utility`
  blocks own what a component *looks* like, the fragments own its *markup*. A new component
  usually needs both, and a variant added to a fragment needs a matching utility or it maps
  to a class literal with no rule behind it.
- **Colour never becomes a hex literal in Kotlin or JS.** `dto/TrainingEventPalette.kt`
  maps a training status to a *token* (`var(--color-…)`) and derives its 10% calendar tint
  with `color-mix`. An inline style resolves `var()` natively; a canvas cannot, so
  `training-stats.js` resolves through `getComputedStyle` before handing colours to
  Chart.js. Changing a token in the stylesheet should move the calendar and both charts
  with no Kotlin edit.
- **A token in `@theme` does not by itself create a utility class.** Tailwind emits
  `.text-title` only once a scanned source references it literally, so a freshly added
  token styles nothing until a template uses it — which looks exactly like a dropped rule
  and is not one. Read the custom property directly (`var(--text-title)`) if you need it
  before then.
- **The default border colour is not a DanceBook token.** The Tailwind v4 compat shim near
  the top of `input.css` sets `border-color: var(--color-gray-200, currentcolor)` on every
  element, and `--color-gray-200` does resolve — to Tailwind's stock
  `oklch(92.8% .006 264.531)`, not to `outline-variant`. So a bare `border` class, which
  the templates carry ~176 of, paints a colour the design system never picked. Name the
  colour (`border border-outline-variant`) rather than relying on the default.
- **Do not use `max-w-{xs,sm,md,lg,xl}`.** The named spacing scale defines
  `--spacing-md` and friends, and a `--spacing-<name>` token shadows the stock
  `--container-<name>`, so `max-w-md` resolves to 24px rather than 28rem. Declaring
  `--container-*` does not win it back. Use an explicit value: `max-w-[28rem]`.
- **Adding an external script/style needs a CSP edit** in `config/SecurityConfig.kt` —
  the policy allowlists only `unpkg.com` (HTMX, SortableJS, Trix), Google Fonts, and Drive.
- **Everything is authenticated** except `/css/**`, `/js/**`, `/images/**`, `/login`.
  Login is form-based *or* Google OAuth2 (`security/CustomOAuth2UserService.kt`) — see
  **Who the current user is** above before reading the principal anywhere.
- Uploaded files live outside the classpath and are served at `/uploads/**` by
  `config/WebMvcConfig.kt`; `uploads/` and `scratch/` are gitignored.
- Do not run production migrations locally — let Flyway run through the application.

## Tests

Mostly plain JUnit 5 + Mockito unit tests against `*ServiceImpl` with mocked
repositories and a mocked `ApplicationEventPublisher`. Integration tests use
`@SpringBootTest` + `@Testcontainers` with a `PostgreSQLContainer` wired via
`@ServiceConnection` (`service/SyllabusImporterIntegrationTest.kt`) — these need Docker
running and the env vars above. `src/test/resources/application-test.properties` holds
dummy datasource values.

**There is no `@DataJpaTest` in this project.** Anything that needs a real database — a
repository query, a Specification predicate, a migration — uses the `@SpringBootTest` +
`@Testcontainers` + `@ServiceConnection` shape above. Do not introduce a `@DataJpaTest`
slice; there is no existing one to model it on.

Migrations get their own tests under `test/.../migration/`, driving Flyway directly against a
Testcontainers Postgres with no Spring context at all, so they need none of the app's env
vars (`migration/TrainingRecordBackfillTest.kt` is the pattern). Migrate to the version
before yours, insert rows, migrate to yours, assert.

MockMvc tests that POST must use `.with(csrf())`, because CSRF protection rejects the
request otherwise. Until #111 this was also true of plain renders: `layout.html` dereferenced
`${_csrf.token}` on every page, so a GET without it threw during render rather than failing an
assertion. Those meta tags are guarded now — an error page that throws while rendering an
error leaves the user with nothing — so a render test no longer needs it to survive.

Two template guards came in with #98 and are cheap to extend, so extend them rather than
working around them. `FragmentCatalogRenderingTest` renders every catalog fragment twice
against a test-only harness template under `src/test/resources/templates/test/`, which is
where a new fragment's two cases go. `HtmxFragmentRenderingTest` pins the root id of every
controller-named htmx fragment.

`UnescapedTemplateOutputTest` (#114) is the third template guard and the one with teeth: it
walks the template tree and fails if unescaped output appears outside
`fragments/rich-text.html`. It starts from zero offenders, so it stays meaningful rather than
grandfathering a list — and it also asserts the permitted fragment still uses unescaped output,
so deleting the one legitimate use cannot quietly turn the test into a tautology.

Two more joined that family, and both exist for the same reason: a construct that parses,
builds and renders without complaint while being wrong. `ThymeleafConditionalIncludeTest`
(#125) fails on a conditional and a fragment inclusion sharing an element;
`FragmentNameAmbiguityTest` (#121) fails on a catalog fragment named after an HTML element.
Each names the offending file and line **and explains the rule in the failure message**,
because a guard against an invisible failure is also the only place the next person will
learn it exists.

**Every GET route is requested on every build.** Both template guards above assert on
fragments and htmx endpoints, so until #105 a template that parsed but threw when rendered
passed the build and 500'd on first visit — which is how #98 shipped a broken Add material
page that survived #101 and was found only by using the app (#103).
`WebRouteSmokeTest` closes that: it discovers every GET mapping on every `@Controller` that
is not a `@RestController` through `RequestMappingHandlerMapping`, and requests each one as
an admin against a seeded domain graph, as its own named `DynamicTest`. Discovery is dynamic,
so **a new route is covered the moment it is added** — a hand-maintained list was ruled out
deliberately. 57 routes today, every one of which must reach HTTP 200.

Two things that test will ask of you. A pattern whose path variable it cannot resolve
throws telling you to add a fixture, rather than skipping the route quietly — so a new
entity type in a URL means a new fixture in `ensureFixtures()` and a branch in
`resolveUri()`. And a route that genuinely cannot reach 200 has to become an explicit named
exception with a stated reason; anything looser lets a 4xx count as covered, which is how
`/training-events/quick-create` was caught returning 400 and rendering nothing.
`ThymeleafRestrictedExpressionTest` still scans statically for the one known
`new`-in-a-fragment-expression pattern, because a static scan names the offending template.

The cost is ~2.2s of requests on top of the Spring context and Testcontainers boot the suite
already pays, in every local build.

`FormValidationWebTest` (#107) is the same idea for the other half of a page: it POSTs invalid
input to all eight form screens and asserts the error actually renders, so a form that
swallows its errors fails the build.

Both of those also assert, since #121, that **no rendered form posts the same field name
twice** — the shape that corrupted descriptions, caught over the rendered HTML so the path
stays closed whatever produces it, not just the fragment collision that opened it. Which
elements may legitimately share a name — radio groups, submit and reset buttons, disabled
controls, and the checkboxes of a multi-select, which share a name but carry distinct values —
is decided once in `controller/web/FormFieldDuplication.kt` and called from both suites.

**Every web test here signs in with form login, which is half the app.** `@WithMockUser` puts
a `UserDetails` in the security context and Google never does, so #122 shipped three comment
endpoints that threw for every Google user with the whole suite green.
`CommentControllerOAuth2Test` (#122) is the shape for the other half: build a real
`DefaultOAuth2User` carrying an `email` attribute and set it with
`TestSecurityContextHolder.setContext(...)` — **not** with the `authentication()` request
post-processor, which needs a security filter chain that an `addFilters = false` slice does not
run, and which therefore leaves the test passing with no principal at all rather than the
non-`UserDetails` one that is the whole point. Anything that reads the current user earns one.

**`ErrorPageIntegrationTest` (#111) is the one test here that does not use MockMvc**, and the
reason is load-bearing: MockMvc does not reliably perform the ERROR dispatch, so a MockMvc
test asserting a 404 status passes just as happily when the error page is broken or
unreachable. It runs against a real servlet container on a random port and drives it over
HTTP. It also does not add an endpoint to crash on — it mocks a service into throwing and
requests a page that already exists, which keeps the test from needing anything loosened in
production security config. **If a test cannot pass without changing production security,
change the test.** A `permitAll` rule that exists only to serve a test is a hole that ships.

## Repo conventions

Issues and PRDs for this repo live as GitHub issues; external pull requests are treated as
a triage surface. See `docs/agents/issue-tracker.md` for the `gh` commands.

Triage status uses the labels `needs-triage`, `needs-info`, `ready-for-agent`,
`ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

Domain docs use a single-context layout — see `docs/agents/domain.md`. That file
references a root `CONTEXT.md` and `docs/adr/`; neither exists yet, so proceed without
them rather than flagging their absence.

Project-specific agent rules and skills live in `.agents/rules/` and `.agents/skills/`.

## Agent delegation contract

Implementation work on an issue labelled `ready-for-agent` may be delegated to an
autonomous agent. If you are that agent, this section is binding.

**The issue you are given is a specification, not a plan.** It states what must be true
when you are done, not which files to edit. Working out the *how* — which files, which
layers, which tests — is your job, and the codebase is the source of truth for it. Do not
wait to be told where the code lives; go and find it.

### The work loop

1. **Orient.** Read `.agy-task.md` and this file. Find the nearest existing feature that
   already does something similar and read it end to end, then copy its shape rather than
   inventing one. The pattern map below is your starting point.
2. **Plan.** Write `.agy-plan.md` *before you edit anything*: the files you will add or
   change and why, the tests you will write, and any ambiguity in the spec together with
   the reading you chose. Keep it under ~40 lines of prose and bullets, with **no code
   snippets** — a human reviewer reads this file, nothing executes it.
   Put the work under a `## Steps` heading as a Markdown checklist (`- [ ] …`), about 4–10
   items in the order you will do them, the last being a green `./gradlew build`.
3. **Implement.** Write the change and its tests together. **Tick each step (`- [x]`) in
   `.agy-plan.md` as soon as it is done**, not at the end: the checklist is how the person
   who delegated the work sees your progress while you run. Add a step if you discover
   one; never delete a step you did not do, leave it unticked and say why under
   `## Decisions`.
4. **Verify.** Run `./gradlew build` until it passes, fixing your own failures. Resolving
   your own compile errors is the entire reason the work was delegated to you.
   The build takes far longer than the 10s your command tool will wait, so the runtime
   *will* detach it into a background task. That is normal. Do not relaunch it — a second
   build while one is in flight is how past runs burned hours without ever seeing a result.
   Wait for the completion notification, read it, and re-run only after you have acted on
   it. You are not finished until you can quote the last two lines of a passing build.
5. **Report.** See *Reporting back* below.

### When the spec is ambiguous

It will be, in places. Pick the reading most consistent with the existing code, implement
it, and record the choice under a `## Decisions` heading in `.agy-plan.md`. Do not stop to
ask — nobody is reading the run live, so a question ends the run without an answer.

### Pattern map — where to find the shape to copy

- CRUD service with domain events → `service/TrainingEventServiceImpl.kt`
- HTMX list/filter page → `controller/web/DanceFigureWebController.kt` plus
  `templates/dance-figures/list.html`
- Dense table with sortable headers and a card form below 1024px →
  `templates/dance-figures/list.html` (`figuresTable`) plus the `js-sort-header` handler in
  `static/js/main.js`
- Admin screen and its fragments → `controller/web/AdminCalendarController.kt`
- Shared UI component → `templates/fragments/` (`page.html` for the header-with-toolbar
  slot, `form.html` for a bound field, `table.html` for a dense table)
- Several views of one section under one nav item → `fragments/page.html :: viewSwitcher`
  on the five `templates/training-events/*` views, plus `TrainingNavAndSwitcherTest`
- Bound form that re-renders its own validation errors →
  `controller/web/CustomListWebController.kt` plus `templates/lists/form.html`
- Dialog → `templates/fragments/modal.html` for a new one, `fragments/confirm-dialog.html`
  for the htmx-delivered confirmation shape
- Test that needs a real servlet container → `controller/web/ErrorPageIntegrationTest.kt`
- Sanitised long-form text, and the one permitted unescaped render →
  `service/RichTextServiceImpl.kt` plus `templates/fragments/rich-text.html`
- Rich text editor field, its toolbar pruning and its blank-submit guard →
  `templates/fragments/form.html :: richText` plus the Trix block at the end of
  `static/js/main.js`
- Test that signs in with Google rather than form login →
  `controller/web/CommentControllerOAuth2Test.kt`
- Service unit test (JUnit 5 + Mockito) → `service/DanceFigureServiceTest.kt`,
  `service/TrainingEventServiceTest.kt`
- Migration plus its Flyway/Testcontainers test → `src/main/resources/db/migration/` and
  `test/.../migration/TrainingRecordBackfillTest.kt`
- Integration test against a real Postgres → `service/TrainingEventUpdateIntegrationTest.kt`
- Entity with an owner and a visibility, its `visibleTo` Specification and its `findById` →
  `repository/MaterialSpecification.kt` plus `service/MaterialServiceImpl.kt`
- Second-user test (user B gets 404, then sees the item once it is public; 403 on changes) →
  `controller/web/AccessControlSecondUserIntegrationTest.kt`

**Definition of done**

- `./gradlew build` passes. This is the only acceptance signal; do not report success
  without it, and do not describe a build as passing that you have not actually run.
- `.agy-plan.md` exists and matches what you actually did.
- The change is confined to the branch/worktree you were given.

**Rules that produce broken builds or invisible features when ignored**

- **Schema change ⇒ Flyway migration.** `spring.jpa.hibernate.ddl-auto=validate`, so any
  entity field without a matching column fails at startup. Add
  `src/main/resources/db/migration/V<next>__description.sql`.
- **Mutating service method ⇒ publish the matching `DomainEvent`.** Otherwise the change
  never reaches the activity feed or the notification badge.
- **New list/filter endpoint ⇒ follow the HTMX shape** in
  `controller/web/DanceFigureWebController.kt` (fragment selector on `HX-Request`).
- **New top-level route ⇒ add a branch to `activeNav()`** in
  `controller/web/NavbarAdvice.kt`. A new view of an existing section is not a top-level
  route: under `/training-events` it joins the `viewSwitcher` (see *Navigation*).
- **A component that exists in `templates/fragments/` ⇒ call it, do not hand-roll it.**
  Headers, buttons, fields, badges, empty states, alerts and modals all live there. A new
  fragment, or a new parameter on one, must be added to `FragmentCatalogRenderingTest` —
  named parameters make a typo silently `null`, and that test is the only thing that says so.
- **A catalog fragment must not be named after an HTML element.** `~{file :: name}` is a
  markup selector, so a fragment named `textarea`, `button` or `header` also matches every
  literal tag of that name in the same file and renders all of them — duplicate form fields
  and saved values that grow on every save, with no error anywhere. Suffix it instead
  (`textareaField`, `actionButton`, `pageHeader`); `FragmentNameAmbiguityTest` fails the
  build if you do not.
- **`@Valid` on a handler ⇒ a `BindingResult` parameter directly after it**, plus a branch
  that repopulates the model and returns the view name. Without it Spring throws instead of
  binding and the user gets the Whitelabel 400 page. Every bound field on the request DTO
  needs a default value too, or binding fails before validation runs and there is no error
  to show. `FormValidationWebTest` covers the eight existing forms; a new one belongs there.
- **A dialog ⇒ a native `<dialog>`, never a `<div>` overlay.** Opening it is one
  `showModal()` call; focus, Escape and the backdrop are the browser's. Writing any of those
  by hand re-creates what #111 deleted.
- **Never rename, move or restructure a fragment a controller names in a string.** Its
  file, root tag, `id` and fragment name are frozen; migrate the contents and leave the
  marker where it is. `HtmxFragmentRenderingTest` will fail if you do not.
- **Colours come from the design tokens** in the `@theme` block of
  `frontend/input.css` — five neutrals, one accent, two status colours, no success green —
  not raw Tailwind palette values, and never as a hex literal in Kotlin or JS. Tailwind
  scans templates, `static/js` and `src/main/kotlin`, so a class name must appear as a
  whole literal in one of those trees to be emitted — never assemble one by concatenation.
- **Long-form text field ⇒ it goes through `RichTextService`**, on write and on read, and
  emptiness is decided by calling `clean()` rather than by testing the string. A raw
  `isNotBlank()` on one of these fields passes editor markup as content and renders an empty
  panel. Never add `th:utext` outside `fragments/rich-text.html`;
  `UnescapedTemplateOutputTest` fails the build if you do.
- **The current user ⇒ `AppUserService.getCurrentUser()`.** Never take it as
  `@AuthenticationPrincipal`: under Google login the principal is not a `UserDetails`, the
  parameter binds `null` and Kotlin throws before your code runs. In a template, compare
  `currentUser.id` to decide ownership, never `#authentication.name` — that name is an email
  under OAuth2 and a username under form login.
- **A page that calls the `richText` fragment ⇒ add the Trix script tag** to that template
  (`materials/form.html` shows where). It is not in the layout, and without it the field is an
  invisible hidden input that no test and no build failure will point at.
- **New external script/style ⇒ update the CSP** in `config/SecurityConfig.kt`.

**Never touch**

- `static/css/output.css` — generated, gitignored.
- `AGENTS.md`, `CLAUDE.md`, `GEMINI.md` — onboarding is set by a human.
- Production migrations. Let Flyway run through the application.
- `git`. Version control is handled outside your session — branches, commits and pull
  requests are not yours to make. Just leave the working tree in the state you want
  reviewed.
- `gh` and the issue tracker. Your plan is published to the issue on your behalf; you do
  not need access, and you do not have it.

**Reporting back**

End by summarising what changed and why, listing the files touched, and stating the
outcome of `./gradlew build` verbatim. If you could not finish, say exactly what is
incomplete rather than implying the work is done.

Write it as a plain report for a reviewer, not as a lesson. No tutorials, no comparisons
to other frameworks, no intuition-building asides, and **never end with a question or a
comprehension check** — nobody is reading the run live to answer it.
