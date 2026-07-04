import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    application
}

application {
    mainClass.set("org.simplepoint.auditing.server.Auditing")
}

val frontendRoot = rootProject.file("simplepoint-react")
val frontendAuditDir = frontendRoot.resolve("modules/simplepoint-audit")
val frontendAuditDistDir = frontendAuditDir.resolve("dist")
val pnpmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "pnpm.cmd"
} else {
    "pnpm"
}

val buildAuditFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the SimplePoint auditing frontend."
    dependsOn(rootProject.tasks.named("installFrontendDependencies"))
    workingDir = frontendRoot
    commandLine(pnpmCommand, "run", "build:audit")

    inputs.files(
        frontendRoot.resolve("package.json"),
        frontendRoot.resolve("pnpm-lock.yaml"),
        frontendRoot.resolve("pnpm-workspace.yaml"),
        frontendAuditDir.resolve("package.json"),
        frontendAuditDir.resolve("module.exposes.ts"),
        frontendAuditDir.resolve("rslib.config.ts"),
        frontendAuditDir.resolve("tsconfig.json")
    )
    inputs.files(fileTree(frontendRoot.resolve("libs")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    inputs.files(fileTree(frontendAuditDir.resolve("src")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    outputs.dir(frontendAuditDistDir)
}

configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAuditFrontend)
    from(frontendAuditDistDir) {
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

    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))

    // 引入服务路由远程调用支持
    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    // 引入API文档
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    // 日志管理
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-rest"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-service"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-monitor"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-api"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-repository"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-rest"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-service"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-api"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-rest"))
     implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-service"))

 }
