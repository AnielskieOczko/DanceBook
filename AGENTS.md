# Project Instructions

- Build the project with `./gradlew build` or `./gradlew clean build`
- Run the application with `./gradlew bootRun`
- For frontend Tailwind CSS builds, the `processResources` task depends on `buildTailwind`
- Run tests with `./gradlew test`
- Use Docker Compose for services: `docker compose up` (PostgreSQL, etc.)
- Do not run production migrations locally; use Flyway via the application
- Keep responses concise and focused on the task at hand

## Agent skills

### Issue tracker

Issues and PRDs for this repo live as GitHub issues. External pull requests are treated as a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage status is tracked using the default label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Uses a single-context domain documentation layout (`CONTEXT.md` and `docs/adr/` at the root). See `docs/agents/domain.md`.