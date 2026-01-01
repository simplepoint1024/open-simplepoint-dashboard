plugins {
    java
    idea
    checkstyle
    id("org.springframework.boot") version libs.versions.spring.boot.get() apply false
    id("io.spring.dependency-management") version libs.versions.spring.dependency.management.get()
    kotlin("jvm") version libs.versions.kotlin.get()
}

allprojects {
    apply(plugin = "checkstyle")
    checkstyle {
        toolVersion = "10.23.0"
        configFile = rootProject.file("checkstyle/google_checks.xml")
        maxWarnings = 0
    }

}

subprojects {
    apply(plugin = "java")
    apply(plugin = "idea")
    apply(plugin = "kotlin")
    version = rootProject.version

    dependencies {
        implementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}"))
        implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${rootProject.libs.versions.spring.cloud.get()}"))
        implementation(platform("org.springframework.security:spring-security-bom:${rootProject.libs.versions.spring.security.get()}"))
        implementation(platform("com.fasterxml.jackson:jackson-bom:${rootProject.libs.versions.jackson.get()}"))
        implementation(platform("org.springdoc:springdoc-openapi-bom:${rootProject.libs.versions.openapi.get()}"))
        implementation(platform ("com.github.victools:jsonschema-generator-bom:${rootProject.libs.versions.jsonschema.generator.get()}"))

        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
        implementation("org.jetbrains.kotlin:kotlin-reflect")

        implementation("com.fasterxml.jackson.core:jackson-databind")
        implementation("com.fasterxml.jackson.core:jackson-core")
        implementation("com.fasterxml.jackson.core:jackson-annotations")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

        implementation("jakarta.persistence:jakarta.persistence-api")
        implementation("cn.hutool:hutool-all:${rootProject.libs.versions.hutool.get()}")

        compileOnly("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")
        annotationProcessor("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")

        testImplementation(platform("org.junit:junit-bom:${rootProject.libs.versions.junit.get()}"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testCompileOnly("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")
        testAnnotationProcessor("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")
    }

    tasks.test {
        useJUnitPlatform()
    }
}

tasks.register<Copy>("installGitHooks") {
    from("scripts/hooks/pre-commit")
    into(".git/hooks")
    rename { "pre-commit" }
}

tasks.named("build") {
    dependsOn("installGitHooks")
}

tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
apply(from = rootProject.file("buildSrc/build.gradle.kts"))