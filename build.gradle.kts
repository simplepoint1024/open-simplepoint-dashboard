import org.gradle.api.tasks.Exec

plugins {
    java
    `java-library`
    idea
    checkstyle
    jacoco
    id("org.springframework.boot") version libs.versions.spring.boot.get() apply false
    id("io.spring.dependency-management") version libs.versions.spring.dependency.management.get()
    kotlin("jvm") version libs.versions.kotlin.get() apply false
}

val frontendRootDir = layout.projectDirectory.dir("simplepoint-react").asFile
val frontendPnpmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "pnpm.cmd"
} else {
    "pnpm"
}

val installFrontendDependencies by tasks.registering(Exec::class) {
    group = "build"
    description = "Installs frontend workspace dependencies for Gradle-driven frontend builds."
    workingDir = frontendRootDir
    commandLine(frontendPnpmCommand, "install", "--frozen-lockfile")

    inputs.files(
        frontendRootDir.resolve("package.json"),
        frontendRootDir.resolve("pnpm-lock.yaml"),
        frontendRootDir.resolve("pnpm-workspace.yaml")
    )
    outputs.dir(frontendRootDir.resolve("node_modules"))
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
    val hasKotlinSources = file("src/main/kotlin").exists() || file("src/test/kotlin").exists()

    apply(plugin = "java-library")
    apply(plugin = "idea")
    apply(plugin = "jacoco")
    if (hasKotlinSources) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
    }
    version = rootProject.version

    dependencies {
        implementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}"))
        implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${rootProject.libs.versions.spring.cloud.get()}"))
        implementation(platform("org.springframework.security:spring-security-bom:${rootProject.libs.versions.spring.security.get()}"))
        implementation(platform("com.fasterxml.jackson:jackson-bom:${rootProject.libs.versions.jackson.get()}"))
        implementation(platform("org.springdoc:springdoc-openapi-bom:${rootProject.libs.versions.openapi.get()}"))
        implementation(platform ("com.github.victools:jsonschema-generator-bom:${rootProject.libs.versions.jsonschema.generator.get()}"))

        implementation("com.fasterxml.jackson.core:jackson-databind")
        implementation("com.fasterxml.jackson.core:jackson-core")
        implementation("com.fasterxml.jackson.core:jackson-annotations")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

        implementation("jakarta.persistence:jakarta.persistence-api")
        implementation("cn.hutool:hutool-all:${rootProject.libs.versions.hutool.get()}")

        compileOnly("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")
        annotationProcessor("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")

        testImplementation(enforcedPlatform("org.junit:junit-bom:${rootProject.libs.versions.junit.get()}"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testCompileOnly("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")
        testAnnotationProcessor("org.projectlombok:lombok:${rootProject.libs.versions.lombok.get()}")

        if (hasKotlinSources) {
            implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
            implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
            implementation("org.jetbrains.kotlin:kotlin-reflect")
        }
    }

    tasks.test {
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        dependsOn(tasks.named("test"))
    }
}

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "Aggregated JaCoCo coverage report for all subprojects"

    val reportTasks = subprojects.mapNotNull { sub ->
        sub.tasks.findByName("jacocoTestReport") as? JacocoReport
    }
    dependsOn(reportTasks)

    val execFiles = subprojects.map { sub ->
        sub.fileTree(sub.buildDir) { include("jacoco/*.exec") }
    }
    executionData.setFrom(execFiles)

    val srcDirs = subprojects.flatMap { sub ->
        listOf(sub.file("src/main/java"), sub.file("src/main/kotlin")).filter { it.exists() }
    }
    sourceDirectories.setFrom(files(srcDirs))

    val classDirs = subprojects.map { sub ->
        sub.fileTree(sub.buildDir) {
            include("classes/java/main/**", "classes/kotlin/main/**")
        }
    }
    classDirectories.setFrom(classDirs)

    reports {
        xml.required.set(true)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregated/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregated/jacocoAggregated.xml"))
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
