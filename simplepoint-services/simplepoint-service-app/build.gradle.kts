import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    application
}

application {
    mainClass.set("org.simplepoint.app.server.ApplicationWorkbenchApplication")
}

val frontendRoot = rootProject.file("simplepoint-react")
val frontendAppDistDir = frontendRoot.resolve("modules/simplepoint-app/dist")
configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    if (!providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).getOrElse(false)) {
        dependsOn(rootProject.tasks.named("buildAppFrontend"))
        from(frontendAppDistDir) {
            into("static")
        }
    }
}

dependencies {
    implementation(project(":simplepoint-events:simplepoint-events-rabbit"))
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
    implementation(project(":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-form-implementation"))

    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}
