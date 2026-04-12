# DanceBook — Project Plan (v2)

## Overview

A personal web app for managing ballroom dance learning resources.
Used daily by 2 people. Primary goal: **learning Spring Boot**.
Mobile-first experience via responsive web app.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.5.x (Kotlin, Java 21) |
| Database | Neon DB (PostgreSQL) — free tier (prod), Docker Postgres (local) |
| Frontend | Thymeleaf + Tailwind CSS v3.4 + HTMX (CDN) |
| Video Storage | Google Drive API v3 (direct browser upload via OAuth, iframe preview) |
| Hosting | Google Cloud Run — free tier |
| CI/CD | GitHub Actions (build + deploy) |
| Code Quality | SonarQube Community Edition (local Docker container) |
| Migrations | Flyway |
| Build | Gradle (Kotlin DSL) + node-gradle plugin (for Tailwind) |

---

## Architecture

```
REST Controller     (@RestController  /api/...)
Web Controller      (@Controller      /...)
        │
        ▼
    Service Layer
        │
        ▼
    Repository Layer (Spring Data JPA)
        │
        ▼
    Neon DB (PostgreSQL)
```

Both REST and Web controllers share the **same service layer**.
No logic duplication. Services are unaware of who calls them.

### Google Drive Video Upload & Playback

```
Upload:   Backend (Refresh Token) ──mints──► Access Token delivered to Browser
          Browser ──Access Token──► Google Drive API (direct, for CORS-enabled resumable upload)
Playback: Browser <iframe src="drive.google.com/file/d/.../preview"> ──► Google Drive
                ↑
      No backend involved for video streaming = zero egress cost
```

**Upload flow:**
1. Frontend fetches short-lived Access Token + folder ID from backend (`/api/materials/upload-config`)
2. Backend generates this token silently using a stored `REFRESH_TOKEN` (UserCredentials)
3. Browser initiates Resumable Upload session directly with Google Drive API (to ensure CORS is allowed)
4. Browser uploads file data directly to Google Drive via `PUT`
5. Frontend calls backend `/api/materials/finalize-upload` to set "anyone with the link" permission
6. Drive file ID saved to Material entity

**Playback:** `<iframe>` embed with `drive.google.com/file/d/{id}/preview`.
Direct `<video>` tag doesn't work due to Google Drive CORS restrictions.

**Security:**
- OAuth scope: `drive.file` — can only access files created through the app
- Security trade-off: The `upload-config` endpoint vends a real Google Drive API token to the frontend. This needs to be protected via Spring Security.
- No Google login popup needed. Complete silent authentication.

---

## Data Model

```
Material
├── id                UUID (PK)
├── name              VARCHAR(255)
├── description       TEXT
├── danceType         ManyToOne → DanceType
├── danceCategory     ManyToOne → DanceCategory
├── rating            SMALLINT (1–5)
├── videoLink         VARCHAR(500) — Google Drive or external URL
├── sourceLink        VARCHAR(500) — Facebook, YouTube, etc.
├── driveFileId       VARCHAR(255) — for Drive-uploaded files
├── createdAt         TIMESTAMP
└── figures           OneToMany → Figure

Figure
├── id                UUID (PK)
├── material          ManyToOne → Material
├── name              VARCHAR(255)
├── startTime         INTEGER (seconds)
└── endTime           INTEGER (seconds)

DanceType
├── id                UUID (PK)
├── name              VARCHAR(100)
└── predefined        BOOLEAN

DanceCategory
├── id                UUID (PK)
├── name              VARCHAR(100)
└── predefined        BOOLEAN
```

---

## Features

### Core Material Management
- CRUD for materials (name, description, dance type, category, rating, links)
- List all materials
- Filter by dance type, category, rating
- HTMX-powered filter updates (no full page reload)

### Dance Types & Categories
- Predefined seed data (Waltz, Tango, Cha Cha, etc.)
- Editable — users can add/edit/delete types and categories
- `predefined` flag distinguishes seeded vs user-created entries

### Video Timestamp Slicing
- Each material can have multiple `Figure` entries
- Each figure has `startTime` and `endTime` in seconds
- Clicking a figure on the material detail page seeks the video player to that timestamp
- Uses HTML5 `<video>` with `#t=start,end` — no physical video cutting
- Works for both Google Drive direct links and external video URLs

### Google Drive Integration
- Upload flow: Backend authenticates via stored Refresh Token -> Hands Access Token to browser -> Browser uploads directly.
- Backend serves Access Token + folder ID via `/api/materials/upload-config`
- Upload uses resumable upload protocol with progress tracking (Frontend-initiated for CORS support)
- Backend endpoint `/api/materials/finalize-upload` sets "anyone with link = viewer" permission for iframe playback
- Video playback: `<iframe>` embed with `drive.google.com/file/d/{id}/preview`
- Videos shared as "Anyone with the link" — acceptable for personal app
- Processing info banner shown for recently uploaded videos

### Authentication
- Currently unprotected, meaning any visitor can upload files to the developer's Google Drive.
- Planned: Phase 5 will implement Spring Security to password-protect the application.

---

## Environment & Database Strategy

### Local Development
- **Database**: Docker Compose with PostgreSQL 16 (see `compose.yaml`)
- **Env vars**: Set via IntelliJ Run Configuration
  - `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- **CSS**: `npx tailwindcss --watch` in parallel with `./gradlew bootRun`

### Production (Cloud Run)
- **Database**: Neon DB (PostgreSQL) — free tier
- **Env vars**: Set as Cloud Run environment variables / secrets
- **CSS**: Compiled during `./gradlew build` (node-gradle plugin)

### application.properties

```properties
# ── Server ──────────────────────────────────────────
spring.application.name=DanceBook

# ── Database ────────────────────────────────────────
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DATABASE_USERNAME}
spring.datasource.password=${DATABASE_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver

# ── JPA ─────────────────────────────────────────────
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# ── Flyway ──────────────────────────────────────────
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration

# ── Virtual Threads ─────────────────────────────────
spring.threads.virtual.enabled=true
```

> ⚠️ Never hardcode credentials. Use environment variables locally via IDE run config.
> In Cloud Run, set them as secrets/env vars.

---

## Flyway Migrations

Naming convention: `V{version}__{description}.sql`

| File | Description |
|---|---|
| `V1__init_schema.sql` | Create all tables |
| `V2__seed_dance_types.sql` | Predefined dance types and categories |

### Rules
- **Never edit** an already-applied migration
- Create a new versioned file for every schema change
- One concern per migration file

---

## Dependencies (`build.gradle.kts`)

```kotlin
plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.node-gradle.node") version "7.1.0"
    kotlin("plugin.jpa") version "1.9.25"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Web + Thymeleaf
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Data
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Validation
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Dev
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

---

## Tailwind CSS Setup (node-gradle)

Tailwind v3.4 via `com.github.node-gradle.node` plugin.
Compilation is hooked into Gradle's `processResources` task.
Running `./gradlew build` automatically compiles CSS.

### Folder Structure

```
src/main/resources/
├── frontend/
│   ├── package.json
│   ├── tailwind.config.js
│   └── input.css
├── static/
│   └── css/
│       └── output.css   ← generated, do not edit
└── templates/
    └── *.html           ← Thymeleaf templates
```

HTMX is included via CDN `<script>` tag in the Thymeleaf layout template:
```html
<script src="https://unpkg.com/htmx.org@2.0.4"></script>
```

### Dev Workflow

Run two processes simultaneously:
1. `./gradlew bootRun` — Spring Boot with DevTools live reload
2. `npx tailwindcss -i ./input.css -o ../static/css/output.css --watch` — CSS watcher

---

## SonarQube (Local Docker)

Run SonarQube Community Edition locally for code quality checks.

### Setup

```yaml
# Add to compose.yaml
  sonarqube:
    image: sonarqube:community
    container_name: dancebook-sonar
    ports:
      - "9000:9000"
    environment:
      SONAR_ES_BOOTSTRAP_CHECKS_DISABLE: "true"
    volumes:
      - sonar-data:/opt/sonarqube/data
      - sonar-logs:/opt/sonarqube/logs
      - sonar-extensions:/opt/sonarqube/extensions

# Add volumes
  sonar-data:
  sonar-logs:
  sonar-extensions:
```

### Usage
1. Start: `docker compose up sonarqube -d`
2. Open: http://localhost:9000 (default login: `admin` / `admin`)
3. Create project + generate token
4. Run analysis: `./gradlew sonar -Dsonar.host.url=http://localhost:9000 -Dsonar.token=<token>`

### Gradle Plugin

Add to `build.gradle.kts`:
```kotlin
plugins {
    id("org.sonarqube") version "6.0.1.5171"
}

sonar {
    properties {
        property("sonar.projectKey", "dancebook")
        property("sonar.projectName", "DanceBook")
    }
}
```

---

## GitHub Actions CI/CD

### CI Pipeline (`.github/workflows/ci.yml`)

Runs on every push/PR to `main`: build + test.

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Set up Node (for Tailwind)
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Build & Test
        run: ./gradlew build
        env:
          DATABASE_URL: ${{ secrets.DATABASE_URL }}
          DATABASE_USERNAME: ${{ secrets.DATABASE_USERNAME }}
          DATABASE_PASSWORD: ${{ secrets.DATABASE_PASSWORD }}
```

### Deploy Pipeline (`.github/workflows/deploy.yml`)

Runs on push to `main` (after CI passes): deploys to Cloud Run.

```yaml
name: Deploy to Cloud Run

on:
  push:
    branches: [ main ]

permissions:
  contents: read
  id-token: write

jobs:
  deploy:
    runs-on: ubuntu-latest
    needs: []  # add "build" job reference if in same file
    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Set up Node (for Tailwind)
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Build
        run: ./gradlew build -x test
        env:
          DATABASE_URL: ${{ secrets.DATABASE_URL }}
          DATABASE_USERNAME: ${{ secrets.DATABASE_USERNAME }}
          DATABASE_PASSWORD: ${{ secrets.DATABASE_PASSWORD }}

      - name: Authenticate to Google Cloud
        uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: ${{ secrets.WIF_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Set up Cloud SDK
        uses: google-github-actions/setup-gcloud@v2

      - name: Configure Docker for Artifact Registry
        run: gcloud auth configure-docker ${{ secrets.GCP_REGION }}-docker.pkg.dev

      - name: Build & Push Docker image
        run: |
          IMAGE="${{ secrets.GCP_REGION }}-docker.pkg.dev/${{ secrets.GCP_PROJECT_ID }}/dancebook/dancebook:${{ github.sha }}"
          docker build -t $IMAGE .
          docker push $IMAGE

      - name: Deploy to Cloud Run
        uses: google-github-actions/deploy-cloudrun@v2
        with:
          service: dancebook
          region: ${{ secrets.GCP_REGION }}
          image: ${{ secrets.GCP_REGION }}-docker.pkg.dev/${{ secrets.GCP_PROJECT_ID }}/dancebook/dancebook:${{ github.sha }}
          env_vars: |
            DATABASE_URL=${{ secrets.DATABASE_URL }}
            DATABASE_USERNAME=${{ secrets.DATABASE_USERNAME }}
            DATABASE_PASSWORD=${{ secrets.DATABASE_PASSWORD }}
```

### Required GitHub Secrets

| Secret | Description |
|---|---|
| `DATABASE_URL` | JDBC connection string |
| `DATABASE_USERNAME` | DB username |
| `DATABASE_PASSWORD` | DB password |
| `GCP_PROJECT_ID` | Google Cloud project ID |
| `GCP_REGION` | e.g. `europe-central2` |
| `GCP_SERVICE_ACCOUNT` | e.g. `github-deployer@project.iam.gserviceaccount.com` |
| `WIF_PROVIDER` | Workload Identity Federation provider resource name |

### GCP One-time Setup

1. Enable APIs: Cloud Run, Artifact Registry, IAM
2. Create Artifact Registry Docker repo: `dancebook`
3. Create service account with roles: `Artifact Registry Writer`, `Cloud Run Admin`, `Service Account User`
4. Set up Workload Identity Federation for GitHub OIDC
5. Create Cloud Run service (first deploy can use `gcloud run deploy` manually)

---

## Dockerfile (Google Cloud Run)

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## Implementation Phases

### Phase 1 — Project Setup & CI/CD ✅
- [x] Neon DB connection configured via environment variables
- [x] Tailwind CSS + HTMX wired up via node-gradle plugin
- [x] Flyway configured, first two migrations applied
- [x] SonarQube local container added to compose.yaml
- [x] GitHub Actions CI pipeline — build goes green
- [x] GitHub Actions deploy pipeline to Cloud Run
- [x] GCP prerequisites: Artifact Registry, service account, WIF
- [x] Smoke test: app boots, connects to DB, renders one page

### Phase 2 — Core CRUD for Materials ✅
- [x] `Material`, `DanceType`, `DanceCategory` JPA entities (Kotlin data classes)
- [x] Repository + Service layer for all three
- [x] REST controllers (`/api/materials`, `/api/dance-types`, `/api/dance-categories`)
- [x] Web controllers + Thymeleaf pages (list, create, edit, delete)
- [x] Filtering by dance type, category, rating
- [x] HTMX partial updates for filters
- [x] Manage screen for dance types and categories

### Phase 3 — Video Timestamp Slicing ✅
- [x] `Figure` JPA entity + Flyway migration
- [x] Add/edit/delete figures linked to a material
- [x] Material detail page with embedded video player (`<iframe>` Google Drive preview)
- [x] Figure list with clickable timestamps (opens Drive preview at timestamp)
- [ ] HTMX for smooth figure interactions (deferred — low priority)

### Phase 4 — Google Drive Integration (Server-Side Auth) ✅
- [x] **Spike**: Tested `<video>` tag — CORS blocks direct playback → using `<iframe>` embed
- [x] GCP setup: OAuth 2.0 Web Application to obtain long-lived Refresh Token
- [x] ~~Browser-side OAuth via Google Identity Services~~ (Replaced with Server-Side Auth)
- [x] Direct browser-to-Drive resumable upload with progress bar (`drive-upload.js`)
- [x] `GoogleDriveProperties` + `GoogleDriveService` using `UserCredentials`
- [x] Backend endpoint `/api/materials/upload-config` (serves auto-refreshed Access Token)
- [x] Frontend initiates Resumable Session directly to satisfy Google CORS policy
- [x] Backend endpoint `/api/materials/finalize-upload` to set file permissions
- [x] Video preview iframe in Material detail page + Processing banner
- [x] Upload error handling in UI

### Phase 5 — Spring Security Authentication ✅
- [x] Implement Spring Security configuration
- [x] Add simple login page with user/password
- [x] Secure all `/api/` and Web endpoints
- [x] Protect the `/api/materials/upload-config` endpoint to prevent unauthorized uploads

### Phase 6 — Storage Maintenance (Cleanup Job) ⏳
- [ ] Schedule a weekly `@Scheduled` task to cross-reference Google Drive files with the database
- [ ] Automatically delete any "orphan" files (in Drive but not in DB) to save storage quota
- [ ] Add a manual "Trigger Cleanup" button in an admin dashboard

---

## Key Principles

- Service layer always — no business logic in controllers
- Kotlin data classes for DTOs — no Lombok needed
- Thymeleaf fragments for reusable UI components (nav, cards, modals)
- One Flyway migration per meaningful schema change
- Never commit credentials to Git
- Test each phase manually before moving to the next
