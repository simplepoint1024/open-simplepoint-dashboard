import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    application
}

application {
    mainClass.set("org.simplepoint.common.server.Common")
}

// These HTTP contracts create many independent Hibernate factories and instrumented repositories.
// Bound per-worker parser/mock metadata lifetime instead of allowing the entire suite to accumulate it.
tasks.test {
    forkEvery = 8
    inputs.files(rootProject.fileTree("simplepoint-react/scripts") { include("**/*.json") })
}

val frontendRoot = rootProject.file("simplepoint-react")
val frontendCommonDir = frontendRoot.resolve("modules/simplepoint-common")
val frontendCommonDistDir = frontendCommonDir.resolve("dist")

tasks.named<ProcessResources>("processTestResources") {
    from(listOf(frontendRoot.resolve("scripts/member-schema.fixture.json"), frontendRoot.resolve("scripts/user-assignment-schema.fixture.json"),
        frontendRoot.resolve("scripts/role-assignment-schema.fixture.json"),
        frontendRoot.resolve("scripts/organization-selection-schema.fixture.json"))) {
        into("query-contracts")
    }
    from(frontendRoot.resolve("scripts/common-main-schemas")) {
        into("query-contracts/common-main")
    }
}

configure<SourceSetContainer> {
    named("main") {
        resources.exclude("static/**")
    }
}

tasks.named<ProcessResources>("processResources") {
    if (!providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).getOrElse(false)) {
        dependsOn(rootProject.tasks.named("buildCommonFrontend"))
        from(frontendCommonDistDir) {
            into("static")
        }
    }
}

dependencies {
    implementation(project(":simplepoint-core"))
    implementation(libs.swagger.annotations)
    api(project(":simplepoint-core"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework.security:spring-security-oauth2-core")

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

    // 引入服务路由远程调用支持
    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))

    // 引入RBAC权限体系核心插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-implementation"))
    // 引入RBAC权限体路由插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-implementation"))
    // 引入OIDC体系插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-rest"))

    // 引入国际化多语言插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-rest"))

    // 引入租户管理插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-implementation"))

    // 引入对象存储插件
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-implementation"))

    // 系统通知、用户收件箱与主动推送
    implementation(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-rest"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-rest"))

    // 引入平台启动编排
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))

    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-monitor"))

    // 引入API文档
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-oauth2-authorization-server")
    testRuntimeOnly("com.h2database:h2")
}
