import com.github.gradle.node.pnpm.task.PnpmTask
import org.gradle.api.JavaVersion
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    `java-library`
    idea
    checkstyle
    jacoco
    id("com.github.node-gradle.node") version "7.1.0"
    id("org.springframework.boot") version libs.versions.spring.boot.get() apply false
    id("io.spring.dependency-management") version libs.versions.spring.dependency.management.get()
    kotlin("jvm") version libs.versions.kotlin.get() apply false
}

val frontendRootDir = layout.projectDirectory.dir("simplepoint-react").asFile

node {
    download.set(true)
    version.set("24.19.0")
    pnpmVersion.set("11.15.1")
    nodeProjectDir.set(layout.projectDirectory.dir("simplepoint-react"))
}

val installFrontendDependencies by tasks.registering(PnpmTask::class) {
    group = "build"
    description = "Installs frontend dependencies with Gradle-managed Node.js and pnpm."
    workingDir.set(frontendRootDir)
    args.set(listOf("install", "--frozen-lockfile"))

    inputs.files(
        frontendRootDir.resolve("package.json"),
        frontendRootDir.resolve("pnpm-lock.yaml"),
        frontendRootDir.resolve("pnpm-workspace.yaml")
    )
    outputs.file(frontendRootDir.resolve("node_modules/.modules.yaml"))
}

fun registerFrontendBuildTask(taskName: String, scriptName: String, moduleName: String) {
    tasks.register<PnpmTask>(taskName) {
        group = "build"
        description = "Builds the SimplePoint $moduleName frontend with the managed toolchain."
        dependsOn(installFrontendDependencies)
        workingDir.set(frontendRootDir)
        args.set(listOf("run", scriptName))

        inputs.files(fileTree(frontendRootDir) {
            include("package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
            include("libs/**", "modules/$moduleName/**")
            exclude("**/node_modules/**", "**/dist/**")
        })
        outputs.dir(frontendRootDir.resolve("modules/$moduleName/dist"))
    }
}

registerFrontendBuildTask("buildCommonFrontend", "build:common", "simplepoint-common")
registerFrontendBuildTask("buildHostFrontend", "build:host", "simplepoint-host")
registerFrontendBuildTask("buildAuditFrontend", "build:audit", "simplepoint-audit")
registerFrontendBuildTask("buildDnaFrontend", "build:dna", "simplepoint-dna")
registerFrontendBuildTask("buildAiFrontend", "build:ai", "simplepoint-ai")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

configure(allprojects.filter { it == rootProject || it.buildFile.isFile }) {
    apply(plugin = "checkstyle")
    checkstyle {
        toolVersion = "10.23.0"
        configFile = rootProject.file("checkstyle/google_checks.xml")
        maxWarnings = 0
    }

}

subprojects {
    if (!buildFile.isFile) {
        // Organizational nodes must not create Java source sets, tests or empty JARs.
        apply(plugin = "base")
        return@subprojects
    }
    val hasKotlinSources = file("src/main/kotlin").exists() || file("src/test/kotlin").exists()

    apply(plugin = "java-library")
    apply(from = rootProject.file("gradle/project-conventions.gradle.kts"))
    apply(plugin = "idea")
    apply(plugin = "jacoco")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
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
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

apply(from = rootProject.file("gradle/module-boundaries.gradle.kts"))

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "Aggregated JaCoCo coverage report for all subprojects"

    val reportTasks = subprojects.mapNotNull { sub ->
        sub.tasks.findByName("jacocoTestReport") as? JacocoReport
    }
    dependsOn(reportTasks)

    val javaProjects = subprojects.filter { it.plugins.hasPlugin("java") }
    val execFiles = javaProjects.map { sub ->
        sub.fileTree(sub.buildDir) { include("jacoco/*.exec") }
    }
    executionData.setFrom(execFiles)

    val srcDirs = javaProjects.flatMap { sub ->
        listOf(sub.file("src/main/java"), sub.file("src/main/kotlin")).filter { it.exists() }
    }
    sourceDirectories.setFrom(files(srcDirs))

    val classDirs = javaProjects.map { sub ->
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
