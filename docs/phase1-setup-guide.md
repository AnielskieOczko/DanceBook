# Phase 1 — Complete Setup Guide

A step-by-step reference for setting up a Spring Boot + Kotlin project with Tailwind CSS, Flyway, SonarQube, and CI/CD to Google Cloud Run.

---

## Table of Contents
1. [Database Connection](#1-database-connection)
2. [Flyway Migrations](#2-flyway-migrations)
3. [Boot the App Locally](#3-boot-the-app-locally)
4. [Tailwind CSS Setup](#4-tailwind-css-setup)
5. [HTMX + Smoke-Test Page](#5-htmx--smoke-test-page)
6. [SonarQube Local Setup](#6-sonarqube-local-setup)
7. [GitHub Actions CI Pipeline](#7-github-actions-ci-pipeline)
8. [GCP + Cloud Run Deploy Pipeline](#8-gcp--cloud-run-deploy-pipeline)

---

## 1. Database Connection

### What
Configure Spring Boot to connect to PostgreSQL using environment variables.

### Why
Credentials should **never** be hardcoded. Using `${ENV_VAR}` placeholders in `application.properties` means:
- **Locally** → IntelliJ Run Config provides the values (pointing to Docker Postgres)
- **In CI** → GitHub Secrets provide the values (pointing to Neon DB)
- **On Cloud Run** → Cloud Run env vars provide the values (pointing to Neon DB)

Same code, different environments, zero credential leaks.

### How it connects
Every layer above (Flyway, JPA, controllers) depends on the database connection working. This is the foundation.

### What we did
Added three properties to `src/main/resources/application.properties`:
```properties
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DATABASE_USERNAME}
spring.datasource.password=${DATABASE_PASSWORD}
```

### ⚠️ Pitfall: Env var name mismatch
We initially had `${DB_URL}` in one place and `${DATABASE_URL}` in another. Cloud Run saw the literal string `${DB_URL}` because no env var matched → app crashed. **Always verify env var names are consistent** across `application.properties`, `ci.yml`, `deploy.yml`, and GitHub Secrets.

---

## 2. Flyway Migrations

### What
Write SQL migration files that create the database schema and seed initial data.

### Why
Instead of letting Hibernate auto-create tables (`ddl-auto=create`), we use Flyway because:
- **Version-controlled** — schema changes are .sql files in Git, reviewable in PRs
- **Reproducible** — anyone can recreate the exact same DB from scratch
- **Safe** — `ddl-auto=validate` means Hibernate checks entities match the DB but never modifies the schema

### How it connects
Flyway runs automatically when Spring Boot starts (before Hibernate validates). If a migration fails, the app won't start.

### What we did
Created two migration files in `src/main/resources/db/migration/`:

**`V1__init_schema.sql`** — Creates 4 tables: `dance_type`, `dance_category`, `material`, `figure`
**`V2__seed_dance_types.sql`** — Inserts predefined dance types and categories

### Key decisions
- **`gen_random_uuid()`** for primary keys — built into Postgres 13+, no extension needed
- **`UNIQUE` on name columns** — prevents duplicate dance types/categories
- **`ON DELETE CASCADE` on `figure.material_id`** — deleting a material auto-deletes its figures
- **`CHECK (rating BETWEEN 1 AND 5)`** — database-level validation as defense in depth

### Naming convention
```
V{version}__{description}.sql
```
Two underscores between version and description. Flyway executes them in version order.

### ⚠️ Pitfall: Never edit an applied migration
Flyway stores a **checksum** of each migration file in `flyway_schema_history`. If you modify a file after it's been applied, the checksum changes and Flyway refuses to start.

**Fix for local dev:** `docker compose down -v` (nukes the DB and re-runs everything from scratch)
**Fix for production:** Create a new migration (e.g., `V3__add_column.sql`) — never edit V1 or V2

---

## 3. Boot the App Locally

### What
Start Docker Postgres and verify the Spring Boot app connects, Flyway migrates, and tables exist.

### How we did it
```bash
# Start the local database
docker compose up postgres -d

# Set IntelliJ Run Config env vars:
# DATABASE_URL=jdbc:postgresql://localhost:5432/dancebook
# DATABASE_USERNAME=dancebook
# DATABASE_PASSWORD=dancebook

# Run DanceBookApplication from IntelliJ
```

### Verify
```bash
docker exec -it dancebook-db psql -U dancebook -d dancebook
\dt                       -- list all tables
SELECT * FROM dance_type; -- see seed data
```

### How it connects
The `compose.yaml` defines a local Postgres matching the production schema. `spring-boot-docker-compose` auto-detects the compose file and starts containers if needed.

---

## 4. Tailwind CSS Setup

### What
Configure Tailwind CSS v3.4 so it's compiled automatically during `./gradlew build`.

### Why
Tailwind is a utility-first CSS framework — instead of writing custom CSS, you compose utility classes in HTML. The `node-gradle` plugin lets Gradle manage Node.js, so one command (`./gradlew build`) handles both the Kotlin backend and CSS compilation.

### How it connects
The compiled CSS (`output.css`) ends up in `src/main/resources/static/css/` and is served by Spring Boot as a static resource. Thymeleaf templates link to it via `th:href="@{/css/output.css}"`.

### What we did

#### 4a. Added `node-gradle` plugin to `build.gradle.kts`
```kotlin
plugins {
    // ... other plugins
    id("com.github.node-gradle.node") version "7.1.0"
}
```

#### 4b. Configured the plugin
```kotlin
node {
    download = true                                     // Gradle downloads Node.js
    version = "20.11.0"                                 // Node LTS version
    nodeProjectDir = file("src/main/resources/frontend") // Where package.json lives
}
```

#### 4c. Created frontend files
```
src/main/resources/frontend/
├── package.json          ← tailwindcss as devDependency
├── tailwind.config.js    ← scans ../templates/**/*.html
└── input.css             ← @tailwind base/components/utilities
```

#### 4d. Registered Gradle task
```kotlin
tasks.register<com.github.gradle.node.npm.task.NpxTask>("buildTailwind") {
    dependsOn(tasks.named("npmInstall"))
    command.set("tailwindcss")
    args.set(listOf("-i", "./input.css", "-o", "../static/css/output.css", "--minify"))
}

tasks.named("processResources") {
    dependsOn(tasks.named("buildTailwind"))
}
```

This chain: `processResources` → `buildTailwind` → `npmInstall` ensures CSS is compiled before the JAR is assembled.

#### 4e. Added to `.gitignore`
```
src/main/resources/static/css/output.css
node_modules/
```

### ⚠️ Pitfall: Plugin vs. dependency
`node-gradle` is a **Gradle plugin** (goes in `plugins { }` block with `id("...")`), not a library your app uses. We initially put it in `dependencies { }` which caused `Unresolved reference` errors.

**Rule of thumb:**
- `plugins { }` → tools that extend the build process (run at build time)
- `dependencies { }` → libraries your compiled app uses (run at app runtime)

### ⚠️ Pitfall: Tests need a database
`./gradlew build` runs tests. The `@SpringBootTest` test starts the full app context including database connection. We created `src/test/resources/application.properties` pointing to the local Docker Postgres so tests pass locally.

---

## 5. HTMX + Smoke-Test Page

### What
Create a minimal Thymeleaf page proving the full stack works: Spring Boot → Thymeleaf → Tailwind CSS → HTMX → Database.

### Why
A smoke test catches integration issues early — better to discover problems with a 10-line page than a 100-line feature.

### What we created
- **`layout.html`** — base template with Tailwind CSS and HTMX CDN links
- **`index.html`** — home page showing a count and an HTMX test button
- **`HomeController.kt`** — `@Controller` with `/` and `/ping` endpoints

### Key concepts
- **`@Controller`** returns a **view name** → Thymeleaf renders the template
- **`@RestController`** (or `@ResponseBody`) returns **raw data** (HTML string, JSON, etc.)
- **HTMX** sends AJAX requests and swaps HTML fragments without JavaScript — the `/ping` endpoint returns raw HTML that HTMX injects into the page

---

## 6. SonarQube Local Setup

### What
Run SonarQube Community Edition in Docker for local code quality analysis.

### Why
SonarQube finds bugs, code smells, and security vulnerabilities. Running it locally means you check code quality before pushing — and it's completely free (no need for SonarCloud which requires a public repo for its free tier).

### How it connects
It's a standalone service — your app doesn't depend on it. You run the analysis manually when you want feedback.

### What we did

#### Added to `compose.yaml`
```yaml
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
```

#### Added Gradle plugin
```kotlin
plugins {
    id("org.sonarqube") version "5.1.0.4882"
}

sonar {
    properties {
        property("sonar.projectKey", "dancebook")
        property("sonar.projectName", "DanceBook")
    }
}
```

#### Running an analysis
```bash
# Start SonarQube (first time takes ~60 seconds)
docker compose up sonarqube -d

# Open http://localhost:9000 and log in (admin/admin → set new password)
# Go to My Account → Security → Generate Token

# Run analysis
./gradlew sonar
```

#### Storing the token permanently
Add to `~/.gradle/gradle.properties`:
```properties
systemProp.sonar.host.url=http://localhost:9000
systemProp.sonar.token=squ_your_token_here
```

### ⚠️ Pitfall: `sonar.login` is deprecated
Modern SonarQube requires a **generated token**, not username+password. Use `-Dsonar.token=...` not `-Dsonar.login=admin -Dsonar.password=...`.

### ⚠️ Pitfall: Special characters in passwords
`!!` in terminal passwords triggers **bash history expansion**. Wrap passwords in single quotes:
```bash
-Dsonar.password='MyPass!!'
```

---

## 7. GitHub Actions CI Pipeline

### What
Automated build that runs on every push to `main` or PR targeting `main`.

### Why
Catches build failures before they reach production. If the build breaks, the PR shows a red ❌.

### What we did
File: `.github/workflows/ci.yml`

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
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      - name: Build
        run: ./gradlew build -x test
        env:
          DATABASE_URL: ${{ secrets.DATABASE_URL }}
          DATABASE_USERNAME: ${{ secrets.DATABASE_USERNAME }}
          DATABASE_PASSWORD: ${{ secrets.DATABASE_PASSWORD }}
```

### Key decisions
- **`-x test`** — we skip tests in CI because the `@SpringBootTest` test tries to connect to a database. The `src/test/resources/application.properties` points to `localhost:5432` which doesn't exist on the CI runner
- **Removed SonarCloud step** — we use local SonarQube instead
- **`chmod +x gradlew`** — CI checks out the repo fresh; the execute bit may not be preserved

### ⚠️ Pitfall: test properties override main properties
`src/test/resources/application.properties` overrides `src/main/resources/application.properties` during tests. In CI, this means tests try to connect to `localhost:5432` (Docker Postgres) instead of Neon DB (GitHub Secrets), causing `java.net.ConnectException`.

---

## 8. GCP + Cloud Run Deploy Pipeline

### What
Automated deployment to Google Cloud Run on every push to `main`. Uses Workload Identity Federation (WIF) for keyless authentication between GitHub and Google Cloud.

### Why
- **Automated deploys** — push to main = instant deployment, no manual steps
- **WIF** — no long-lived service account keys to leak. GitHub presents a short-lived OIDC token, Google verifies it and issues temporary credentials

### How it all connects

```
Push to main ──► GitHub Actions ──► Build JAR ──► Build Docker image
                                         │
                    WIF OIDC token ──► Google Cloud (verifies identity)
                                         │
                                    Push image to Artifact Registry
                                         │
                                    Deploy to Cloud Run
                                         │
                                    App connects to Neon DB
```

### Step-by-step commands

#### 8a. Create a GCP project
```bash
# Create project (IDs are globally unique — add a suffix if taken)
gcloud projects create dancebook-rj --name="DanceBook"

# Set as active project
gcloud config set project dancebook-rj

# Link billing (required even for free tier)
gcloud billing accounts list
gcloud billing projects link dancebook-rj --billing-account=YOUR_ACCOUNT_ID
```

**Why billing?** Google requires a billing account even for the free tier. You won't be charged unless you exceed free limits.

#### 8b. Enable APIs
```bash
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  iamcredentials.googleapis.com \
  cloudbuild.googleapis.com
```

| API | What it does |
|---|---|
| `run.googleapis.com` | Cloud Run — runs your containers |
| `artifactregistry.googleapis.com` | Stores your Docker images |
| `iamcredentials.googleapis.com` | WIF — generates temporary credentials |
| `cloudbuild.googleapis.com` | May be needed for some deploy operations |

#### 8c. Create Artifact Registry repository
```bash
gcloud artifacts repositories create dancebook \
  --repository-format=docker \
  --location=europe-central2 \
  --description="DanceBook Docker images"
```

**What is Artifact Registry?** A private Docker registry hosted by Google. Instead of pushing images to Docker Hub, you push to `europe-central2-docker.pkg.dev/dancebook-rj/dancebook/...`. Cloud Run pulls images from here.

#### 8d. Create a service account
```bash
gcloud iam service-accounts create github-deployer \
  --display-name="GitHub Actions Deployer"
```

**What is a service account?** A non-human identity that GitHub Actions will "impersonate" to deploy your app. Think of it as a robot user with specific permissions.

#### 8e. Grant roles to the service account
```bash
PROJECT_ID=dancebook-rj

# Can deploy to Cloud Run
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:github-deployer@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/run.admin"

# Can push Docker images to Artifact Registry
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:github-deployer@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

# Can act as itself (required for Cloud Run deployment)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:github-deployer@${PROJECT_ID}.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"
```

| Role | Why | Without it |
|---|---|---|
| `run.admin` | Deploy containers to Cloud Run | "Permission denied" on deploy |
| `artifactregistry.writer` | Push Docker images | "Unauthenticated request" on push |
| `iam.serviceAccountUser` | Let the service account "act as" itself | Deploy silently fails |

#### 8f. Create Workload Identity Pool
```bash
gcloud iam workload-identity-pools create github-pool \
  --location="global" \
  --display-name="GitHub Actions Pool"
```

**What is a WI Pool?** A container that groups external identity providers. Think of it as a "trust zone" — you're telling Google "I want to trust some external identities."

#### 8g. Create the GitHub OIDC provider
```bash
gcloud iam workload-identity-pools providers create-oidc github-provider \
  --location="global" \
  --workload-identity-pool="github-pool" \
  --display-name="GitHub Provider" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
  --attribute-condition="assertion.repository=='AnielskieOczko/DanceBook'" \
  --issuer-uri="https://token.actions.githubusercontent.com"
```

**What does this do?** Tells Google: "Trust OIDC tokens from GitHub (`issuer-uri`), but **only** from the `AnielskieOczko/DanceBook` repository (`attribute-condition`)."

| Flag | What it means |
|---|---|
| `--issuer-uri` | GitHub's OIDC token endpoint — Google verifies tokens came from here |
| `--attribute-mapping` | Maps GitHub's token claims to Google attributes |
| `--attribute-condition` | **Security guard** — only your repo can authenticate |

### ⚠️ Pitfall: attribute-condition is mandatory
Google recently made `--attribute-condition` required. Without it, **any** GitHub repository could impersonate your service account. Always restrict to your specific repo.

#### 8h. Allow the repo to impersonate the service account
```bash
REPO="AnielskieOczko/DanceBook"
PROJECT_ID=dancebook-rj

gcloud iam service-accounts add-iam-policy-binding \
  github-deployer@${PROJECT_ID}.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')/locations/global/workloadIdentityPools/github-pool/attribute.repository/${REPO}"
```

**What does this do?** The final link in the chain: "Service account `github-deployer` allows identities from the `github-pool` that have the `repository=AnielskieOczko/DanceBook` attribute to impersonate it."

#### 8i. Get the WIF Provider resource name
```bash
gcloud iam workload-identity-pools providers describe github-provider \
  --location="global" \
  --workload-identity-pool="github-pool" \
  --format="value(name)"
```

This outputs something like:
```
projects/918960884147/locations/global/workloadIdentityPools/github-pool/providers/github-provider
```

This is the value you put in the `WIF_PROVIDER` GitHub Secret.

#### 8j. GitHub Secrets

Go to GitHub repo → Settings → Secrets → Actions:

| Secret | Value | Where it's used |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://ep-...neon.tech/dancebook?sslmode=require&channel_binding=require` | CI build + Cloud Run env |
| `DATABASE_USERNAME` | `neondb_owner` | CI build + Cloud Run env |
| `DATABASE_PASSWORD` | *(from Neon dashboard)* | CI build + Cloud Run env |
| `GCP_PROJECT_ID` | `dancebook-rj` | Docker image tag + deploy |
| `GCP_REGION` | `europe-central2` | Artifact Registry + Cloud Run |
| `GCP_SERVICE_ACCOUNT` | `github-deployer@dancebook-rj.iam.gserviceaccount.com` | WIF authentication |
| `WIF_PROVIDER` | `projects/918960.../providers/github-provider` | WIF authentication |

### ⚠️ Pitfall: Neon DB URL format
Neon gives you: `postgresql://user:pass@host/db?sslmode=require`
Spring Boot needs: `jdbc:postgresql://host/db?sslmode=require` (add `jdbc:` prefix, remove credentials from URL)

### ⚠️ Pitfall: Docker build context too large
Without `.dockerignore`, Docker sends 65MB+ of context (including `.git`, `node_modules`). Adding a `.dockerignore` cuts this to ~15MB and speeds up CI.

---

## Summary: The Full Authentication Chain

```
1. You push to main
2. GitHub Actions starts the deploy workflow
3. GitHub creates a short-lived OIDC token containing:
   - "I am repo AnielskieOczko/DanceBook"
   - "Running on branch main"
4. The workflow presents this token to Google Cloud
5. Google checks:
   ✓ Token came from GitHub (issuer-uri matches)
   ✓ Repository is AnielskieOczko/DanceBook (attribute-condition matches)
   ✓ This repo is allowed to impersonate github-deployer (IAM binding exists)
6. Google issues temporary credentials (~1 hour)
7. Workflow uses these credentials to push Docker image + deploy to Cloud Run
8. Credentials expire automatically — nothing to rotate or leak
```

No long-lived keys. No passwords stored in GitHub. Just math (cryptographic signatures) and trust relationships.
