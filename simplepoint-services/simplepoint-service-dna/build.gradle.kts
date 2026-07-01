import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    application
}

application {
    mainClass.set("org.simplepoint.dna.server.DnaApplication")
}

val frontendRoot = rootProject.file("simplepoint-react")
val frontendDnaDir = frontendRoot.resolve("modules/simplepoint-dna")
val frontendDnaDistDir = frontendDnaDir.resolve("dist")
val pnpmCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
    "pnpm.cmd"
} else {
    "pnpm"
}

val buildDnaFrontend by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the SimplePoint DNA frontend."
    dependsOn(rootProject.tasks.named("installFrontendDependencies"))
    workingDir = frontendRoot
    commandLine(pnpmCommand, "run", "build:dna")

    inputs.files(
        frontendRoot.resolve("package.json"),
        frontendRoot.resolve("pnpm-lock.yaml"),
        frontendRoot.resolve("pnpm-workspace.yaml"),
        frontendDnaDir.resolve("package.json"),
        frontendDnaDir.resolve("module.exposes.ts"),
        frontendDnaDir.resolve("rslib.config.ts"),
        frontendDnaDir.resolve("tsconfig.json")
    )
    inputs.files(fileTree(frontendRoot.resolve("libs")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    inputs.files(fileTree(frontendDnaDir.resolve("src")) {
        exclude("**/node_modules/**", "**/dist/**")
    })
    outputs.dir(frontendDnaDistDir)
}

configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildDnaFrontend)
    from(frontendDnaDistDir) {
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

    // 引入菜单API,用于自动初始化菜单
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))

    // 引入服务路由远程调用支持
    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    // 引入API文档
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-rest"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-rest"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-service"))

    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-rest"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-monitor"))

}
