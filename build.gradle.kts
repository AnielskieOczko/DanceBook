
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
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(".org.springframework.boot:spring-boot-starter-validation")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
}

tasks.register<com.github.gradle.node.npm.task.NpxTask>("buildTailwind") {
    group = "build"
    description = "Builds the Tailwind task"
    dependsOn(tasks.named("npmInstall"))
    command.set("tailwindcss")
    args.set(listOf(
        "-i", "./input.css",
        "-o", "../static/css/output.css",
        "--minify"
    ))
}

tasks.named("processResources") {
    dependsOn(tasks.named("buildTailwind"))
}

sonar {
    properties {
        property("sonar.projectKey", "dancebook")
        property("sonar.projectName", "DanceBook")
    }
}
