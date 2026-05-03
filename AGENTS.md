# Project Instructions

- Build the project with `./gradlew build` or `./gradlew clean build`
- Run the application with `./gradlew bootRun`
- For frontend Tailwind CSS builds, the `processResources` task depends on `buildTailwind`
- Run tests with `./gradlew test`
- Use Docker Compose for services: `docker compose up` (PostgreSQL, etc.)
- Do not run production migrations locally; use Flyway via the application
- Keep responses concise and focused on the task at hand