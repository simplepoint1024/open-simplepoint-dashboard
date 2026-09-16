plugins {
    application
}

application {
    mainClass.set("org.simplepoint.ai.server.AiApplication")
}

val frontendRoot = rootProject.file("simplepoint-react")
val frontendAiDir = frontendRoot.resolve("modules/simplepoint-ai")
val frontendAiDistDir = frontendAiDir.resolve("dist")
configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    if (!providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).getOrElse(false)) {
        dependsOn(rootProject.tasks.named("buildAiFrontend"))
        from(frontendAiDistDir) {
            into("static")
        }
    }
}

dependencies {
    implementation(project(":simplepoint-core"))
    implementation("org.springframework:spring-webmvc")
    implementation("jakarta.servlet:jakarta.servlet-api")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    implementation(libs.swagger.annotations)
    testImplementation("org.springframework.boot:spring-boot-starter-test")

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
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-catalog-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-catalog-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-runtime-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-runtime-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-skill-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-skill-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-agent-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-agent-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-workflow-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-workflow-implementation"))

    // Register AI routes and i18n bundles with the platform services.
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))

    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    // Forward WARN/ERROR events to the centralized auditing service.
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-monitor"))

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
}
