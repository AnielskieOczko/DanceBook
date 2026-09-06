# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew build                 # compile + test + build Tailwind CSS
./gradlew bootRun               # run the app on :8080
./gradlew test                  # all tests
./gradlew test --tests "com.jankowski.rafal.dancebook.service.DanceFigureServiceTest"
./gradlew test --tests "*DanceFigureServiceTest.should create figure*"
./gradlew buildTailwind         # regenerate static/css/output.css only
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
PostgreSQL + Flyway · Thymeleaf + HTMX 2 + Tailwind 3 · Gradle Kotlin DSL.
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

### HTMX partial rendering

Web controllers accept `@RequestHeader("HX-Request", required = false) isHtmxRequest: Boolean?`
and return either a full view name or a fragment selector:

```kotlin
return if (isHtmxRequest == true) "dance-figures/list :: figuresTable" else "dance-figures/list"
```

They also skip loading filter-dropdown data on HTMX requests. Follow this shape for new
list/filter endpoints (`controller/web/DanceFigureWebController.kt` is the reference).

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
  `src/main/resources/db/migration/V<next>__description.sql` (currently at V24).
  `config/FlywayConfig.kt` runs `repair()` before `migrate()` on every boot.
- **Tailwind only scans templates.** `frontend/tailwind.config.js` has
  `content: ['../templates/**/*.html']` — classes generated in `static/js/*.js` are
  **not** emitted. Put dynamically-applied classes in a template or safelist them.
  `static/css/output.css` is generated and gitignored; never edit it by hand.
- **Colors come from the "Noble Harmony" token set** in `tailwind.config.js`
  (`surface`, `on-surface`, `primary`, `outline-variant`, …). Use those tokens rather
  than raw Tailwind palette values.
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

## Repo conventions

`AGENTS.md` points at `docs/agents/` for issue-tracker and triage-label workflow:
issues and PRDs live as GitHub issues, triaged with the labels `needs-triage`,
`needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. `docs/agents/domain.md`
references a root `CONTEXT.md` and `docs/adr/` — neither exists yet; proceed without
them rather than flagging their absence.
