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

Other aggregates: `Material` (video + figures + comments), `CustomList` (user-curated,
optionally public), `Choreography` → ordered `ChoreographyEntry` list, `AppUser`.

### Domain events → activity feed

Services publish subclasses of the sealed `model/DomainEvent.kt` through
`ApplicationEventPublisher`. `service/ActivityEventListener.kt` consumes them with
`@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` and persists
`ActivityEvent` rows, which drive the notifications page and navbar unread badge.
**When you add a mutating service method, publish the matching event** — otherwise the
change is invisible in the activity feed.

### Thymeleaf fragment catalog

`templates/fragments/` is the component vocabulary, and since #98 there is **one source per
component**: `icon`, `button` (`link`, `button`, `iconButton`, `submitRow`), `form`
(`field`, `select`, `textarea`, `checkbox`, `toggle`, `richText`, `errorSummary`,
`rawField`), `page` (`header`, `sectionTitle`), `card`, `table` (`dataTable`, `stepTable`),
`badge`, `empty`, `alert`, `modal` and `rich-text`. Do not hand-roll a page header, a
badge or an empty state in a template — the fragment exists, and the reason #98 was worth
doing is that six training pages had each copied the header instead.

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
rather than binding, which reaches the user as the Whitelabel 400 page — and through an htmx
swap, as a page of error HTML dropped inside the current one. That was live on the admin user
handlers until #107.

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
  the policy allowlists only `unpkg.com` (HTMX, SortableJS), Google Fonts, and Drive.
- **Everything is authenticated** except `/css/**`, `/js/**`, `/images/**`, `/login`.
  Login is form-based *or* Google OAuth2 (`security/CustomOAuth2UserService.kt`).
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
3. **Implement.** Write the change and its tests together.
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
- Admin screen and its fragments → `controller/web/AdminCalendarController.kt`
- Shared UI component → `templates/fragments/` (`page.html` for the header-with-toolbar
  slot, `form.html` for a bound field, `table.html` for a dense table)
- Bound form that re-renders its own validation errors →
  `controller/web/CustomListWebController.kt` plus `templates/lists/form.html`
- Dialog → `templates/fragments/modal.html` for a new one, `fragments/confirm-dialog.html`
  for the htmx-delivered confirmation shape
- Test that needs a real servlet container → `controller/web/ErrorPageIntegrationTest.kt`
- Service unit test (JUnit 5 + Mockito) → `service/DanceFigureServiceTest.kt`,
  `service/TrainingEventServiceTest.kt`
- Migration plus its Flyway/Testcontainers test → `src/main/resources/db/migration/` and
  `test/.../migration/TrainingRecordBackfillTest.kt`
- Integration test against a real Postgres → `service/TrainingEventUpdateIntegrationTest.kt`

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
  `controller/web/NavbarAdvice.kt`.
- **A component that exists in `templates/fragments/` ⇒ call it, do not hand-roll it.**
  Headers, buttons, fields, badges, empty states, alerts and modals all live there. A new
  fragment, or a new parameter on one, must be added to `FragmentCatalogRenderingTest` —
  named parameters make a typo silently `null`, and that test is the only thing that says so.
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
