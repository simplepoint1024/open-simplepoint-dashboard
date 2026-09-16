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
configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    if (!providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).getOrElse(false)) {
        dependsOn(rootProject.tasks.named("buildDnaFrontend"))
        from(frontendDnaDistDir) {
            into("static")
        }
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
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))

    // 引入服务路由远程调用支持
    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    // 引入API文档
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-implementation"))

    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-monitor"))

}
