import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    application
}

application {
    mainClass.set("org.simplepoint.gateway.server.Host")
}

val frontendRoot = rootProject.file("simplepoint-react")
val frontendHostDir = frontendRoot.resolve("modules/simplepoint-host")
val frontendHostDistDir = frontendHostDir.resolve("dist")
val pnpmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "pnpm.cmd"
} else {
    "pnpm"
}

val buildHostFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the SimplePoint host frontend."
    dependsOn(rootProject.tasks.named("installFrontendDependencies"))
    workingDir = frontendRoot
    commandLine(pnpmCommand, "run", "build:host")

    inputs.files(
        frontendRoot.resolve("package.json"),
        frontendRoot.resolve("pnpm-lock.yaml"),
        frontendRoot.resolve("pnpm-workspace.yaml"),
        frontendHostDir.resolve("package.json"),
        frontendHostDir.resolve("rsbuild.config.ts"),
        frontendHostDir.resolve("tsconfig.json")
    )
    inputs.files(fileTree(frontendRoot.resolve("libs")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    inputs.files(fileTree(frontendHostDir.resolve("src")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    inputs.files(fileTree(frontendHostDir.resolve("public")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    outputs.dir(frontendHostDistDir)
}

configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildHostFrontend)
    from(frontendHostDistDir) {
        into("static")
    }
}

dependencies {
    implementation(project(":simplepoint-boot:simplepoint-boot-starter"))
    implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.cloud:spring-cloud-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

//    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.session:spring-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    api(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))

    api(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-monitor"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-gateway"))

    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
