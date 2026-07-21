import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    application
}

application {
    mainClass.set("org.simplepoint.ai.server.AiApplication")
}

val frontendRoot = rootProject.file("simplepoint-react")
val frontendAiDir = frontendRoot.resolve("modules/simplepoint-ai")
val frontendAiDistDir = frontendAiDir.resolve("dist")
val pnpmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "pnpm.cmd"
} else {
    "pnpm"
}

val buildAiFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the SimplePoint AI frontend."
    dependsOn(rootProject.tasks.named("installFrontendDependencies"))
    workingDir = frontendRoot
    commandLine(pnpmCommand, "run", "build:ai")

    inputs.files(
        frontendRoot.resolve("package.json"),
        frontendRoot.resolve("pnpm-lock.yaml"),
        frontendRoot.resolve("pnpm-workspace.yaml"),
        frontendAiDir.resolve("package.json"),
        frontendAiDir.resolve("module.exposes.ts"),
        frontendAiDir.resolve("rslib.config.ts"),
        frontendAiDir.resolve("tsconfig.json")
    )
    inputs.files(fileTree(frontendRoot.resolve("libs")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    inputs.files(fileTree(frontendAiDir.resolve("src")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    outputs.dir(frontendAiDistDir)
}

configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAiFrontend)
    from(frontendAiDistDir) {
        into("static")
    }
}

dependencies {
    implementation(project(":simplepoint-boot:simplepoint-boot-starter"))
    implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-security:simplepoint-security-servlet"))
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    implementation(project(":simplepoint-cache:simplepoint-cache-redis"))

    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-rest"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-rest"))

    // Register AI routes and i18n bundles with the platform services.
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))

    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
}
