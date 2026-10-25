# Stage 1: unpack the Spring Boot fat jar. A class data sharing (CDS) archive can only be
# trained on the extracted layout, not on the nested jar.
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /build
# Named so the glob cannot also match the -plain jar that Gradle builds next to it.
COPY build/libs/DanceBook-*-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=extract /build/extracted/ ./

# Train the CDS archive at build time: start the app up to context refresh, then exit, and
# dump every class that was loaded. Cold starts on Cloud Run then map the classes instead of
# parsing and verifying them again (about 7s down to about 4s locally). No database is
# needed: Flyway and schema validation are switched off for this one run, and the
# placeholders only satisfy property resolution. If the archive is missing or does not
# match the JVM, the JVM warns and starts normally, so the build fails here instead.
RUN DATABASE_URL=jdbc:postgresql://localhost:5432/none DATABASE_USERNAME=none DATABASE_PASSWORD=none \
    GOOGLE_CLIENT_ID=none GOOGLE_CLIENT_SECRET=none GOOGLE_REFRESH_TOKEN=none GOOGLE_DRIVE_FOLDER_ID=none \
    java -XX:ArchiveClassesAtExit=app.jsa -Dspring.context.exit=onRefresh \
    -Dspring.flyway.enabled=false -Dspring.jpa.hibernate.ddl-auto=none \
    -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
    -jar app.jar \
    && test -s app.jsa

EXPOSE 8080
ENTRYPOINT ["java", "-XX:SharedArchiveFile=app.jsa", "-jar", "app.jar"]
