
plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("plugin.jpa") version "1.9.25"
    id("com.github.node-gradle.node") version "7.1.0"
    id("org.sonarqube") version "5.1.0.4882"
}

node {
    download = true
    version = "20.11.0"
    nodeProjectDir = file("src/main/resources/frontend")
}

group = "com.jankowski.rafal"
version = "0.0.1-SNAPSHOT"
description = "DanceBook"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Google Drive API (server-side upload via stored refresh token)
    implementation("com.google.api-client:google-api-client:2.7.2")
    implementation("com.google.apis:google-api-services-drive:v3-rev20250220-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.34.0")

    // Google Calendar API (training calendar sync, same stored-refresh-token model)
    implementation("com.google.apis:google-api-services-calendar:v3-rev20260517-2.0.0")

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

// Tailwind 4. The CLI now ships in its own package, @tailwindcss/cli, which is what
// provides the `tailwindcss` bin here; the `tailwindcss` package alone no longer does.
// Sources are pinned in input.css via `source(none)` plus explicit @source globs, so the
// inputs below must stay in step with them or Gradle will call the task up to date after
// a template change and serve stale CSS.
tasks.register<com.github.gradle.node.npm.task.NpxTask>("buildTailwind") {
    group = "build"
    description = "Compiles input.css into static/css/output.css with the Tailwind CLI"
    dependsOn(tasks.named("npmInstall"))
    command.set("tailwindcss")
    args.set(listOf(
        "-i", "./input.css",
        "-o", "../static/css/output.css",
        "--minify"
    ))

    inputs.file("src/main/resources/frontend/input.css")
    inputs.file("src/main/resources/frontend/package.json")
    inputs.dir("src/main/resources/templates")
    inputs.dir("src/main/resources/static/js")
    inputs.dir("src/main/kotlin")
    outputs.file("src/main/resources/static/css/output.css")
}

// Rebuilds output.css on every change to a scanned source. Run alongside bootRun while
// working on templates: ./gradlew watchTailwind
tasks.register<com.github.gradle.node.npm.task.NpxTask>("watchTailwind") {
    group = "application"
    description = "Watches sources and rebuilds output.css until interrupted"
    dependsOn(tasks.named("npmInstall"))
    command.set("tailwindcss")
    args.set(listOf(
        "-i", "./input.css",
        "-o", "../static/css/output.css",
        "--watch"
    ))
}

tasks.named("processResources") {
    dependsOn(tasks.named("buildTailwind"))
}

tasks.register<JavaExec>("processFigures") {
    group = "application"
    description = "Processes crawled figures using OpenRouter LLM agent"
    classpath = files(provider {
        project.extensions.getByType(SourceSetContainer::class.java)["main"].runtimeClasspath
    })
    mainClass.set("com.jankowski.rafal.dancebook.scripts.FigureProcessorKt")
}

tasks.register<JavaExec>("generateFiguresSql") {
    group = "application"
    description = "Generates V23 database migration seed script from parsed JSON figures"
    classpath = files(provider {
        project.extensions.getByType(SourceSetContainer::class.java)["main"].runtimeClasspath
    })
    mainClass.set("com.jankowski.rafal.dancebook.scripts.SqlGeneratorKt")
}

tasks.register<JavaExec>("generateReconciliationReport") {
    group = "application"
    description = "Generates a detailed reconciliation report matching standard figures with parsed JSON figures"
    classpath = files(provider {
        project.extensions.getByType(SourceSetContainer::class.java)["main"].runtimeClasspath
    })
    mainClass.set("com.jankowski.rafal.dancebook.scripts.GenerateReconciliationReportKt")
}

springBoot {
    mainClass.set("com.jankowski.rafal.dancebook.DanceBookApplicationKt")
}

sonar {
    properties {
        property("sonar.projectKey", "dancebook")
        property("sonar.projectName", "DanceBook")
    }
}
