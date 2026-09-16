rootProject.name = "simplepoint-main"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("./buildSrc/libs.versions.toml"))
        }
    }
}

pluginManagement {
    repositories {
        mavenLocal()
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://repo.spring.io/release") }
    }
}

// Explicitly register buildable modules with directory-aligned project paths.
// Gradle's implicit parent projects provide the hierarchy used by IDE imports;
// only the listed modules receive Java/build conventions in the root build.
val moduleDirectories = listOf(
    "simplepoint-api",
    "simplepoint-boot/simplepoint-boot-config-consul-starter",
    "simplepoint-boot/simplepoint-boot-starter",
    "simplepoint-cache/simplepoint-cache-core",
    "simplepoint-cache/simplepoint-cache-redis",
    "simplepoint-core",
    "simplepoint-data-extend/simplepoint-data-cp-endpoint",
    "simplepoint-data/simplepoint-data-calcite/simplepoint-data-calcite-core",
    "simplepoint-data/simplepoint-data-cp",
    "simplepoint-data/simplepoint-data-jdbc",
    "simplepoint-data/simplepoint-data-jpa",
    "simplepoint-data/simplepoint-data-json/simplepoint-data-json-schema",
    "simplepoint-data/simplepoint-data-mongodb",
    "simplepoint-data/simplepoint-data-r2dbc",
    "simplepoint-examples/simplepoint-plugin-examples/simplepoint-plugin-example-app",
    "simplepoint-examples/simplepoint-plugin-examples/simplepoint-plugin-example-controller",
    "simplepoint-examples/simplepoint-plugin-examples/simplepoint-plugin-example-service",
    "simplepoint-examples/simplepoint-plugin-examples/simplepoint-plugin-example-service-api",
    "simplepoint-examples/simplepoint-service-router-examples/simplepoint-service-router-example-api",
    "simplepoint-examples/simplepoint-service-router-examples/simplepoint-service-router-example-consumer",
    "simplepoint-examples/simplepoint-service-router-examples/simplepoint-service-router-example-provider",
    "simplepoint-platform/simplepoint-platform-bootstrap",
    "simplepoint-plugin/simplepoint-plugin-api",
    "simplepoint-plugin/simplepoint-plugin-core",
    "simplepoint-plugin/simplepoint-plugin-spring",
    "simplepoint-plugin/simplepoint-plugin-webmvc",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-agent-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-agent-implementation",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-catalog-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-catalog-implementation",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-core-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-core-implementation",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-knowledge-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-knowledge-implementation",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-mcp-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-mcp-implementation",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-runtime-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-runtime-implementation",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-skill-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-skill-implementation",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-workflow-api",
    "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-workflow-implementation",
    "simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-core-api",
    "simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-core-implementation",
    "simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-federation-api",
    "simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-federation-implementation",
    "simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-jdbc-driver",
    "simplepoint-plugins/simplepoint-plugin-notification/simplepoint-plugin-notification-api",
    "simplepoint-plugins/simplepoint-plugin-notification/simplepoint-plugin-notification-repository",
    "simplepoint-plugins/simplepoint-plugin-notification/simplepoint-plugin-notification-rest",
    "simplepoint-plugins/simplepoint-plugin-notification/simplepoint-plugin-notification-service",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-logging-api",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-logging-implementation",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-logging-monitor",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-rate-limit-api",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-rate-limit-gateway",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-rate-limit-implementation",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-redis-api",
    "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-redis-implementation",
    "simplepoint-plugins/simplepoint-plugins-i18n/simplepoint-plugin-i18n-api",
    "simplepoint-plugins/simplepoint-plugins-i18n/simplepoint-plugin-i18n-repository",
    "simplepoint-plugins/simplepoint-plugins-i18n/simplepoint-plugin-i18n-rest",
    "simplepoint-plugins/simplepoint-plugins-i18n/simplepoint-plugin-i18n-service",
    "simplepoint-plugins/simplepoint-plugins-oidc/simplepoint-plugin-oidc-api",
    "simplepoint-plugins/simplepoint-plugins-oidc/simplepoint-plugin-oidc-repository",
    "simplepoint-plugins/simplepoint-plugins-oidc/simplepoint-plugin-oidc-rest",
    "simplepoint-plugins/simplepoint-plugins-oidc/simplepoint-plugin-oidc-service",
    "simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-core-api",
    "simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-core-implementation",
    "simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-router-api",
    "simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-router-implementation",
    "simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-router-repository",
    "simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-tenant-api",
    "simplepoint-plugins/simplepoint-plugins-rbac/simplepoint-plugin-rbac-tenant-implementation",
    "simplepoint-plugins/simplepoint-plugins-storage/simplepoint-plugin-storage-api",
    "simplepoint-plugins/simplepoint-plugins-storage/simplepoint-plugin-storage-http-client",
    "simplepoint-plugins/simplepoint-plugins-storage/simplepoint-plugin-storage-implementation",
    "simplepoint-remoting/simplepoint-remoting-core",
    "simplepoint-security/simplepoint-security-core",
    "simplepoint-security/simplepoint-security-oauth2-client",
    "simplepoint-security/simplepoint-security-oauth2-resource",
    "simplepoint-security/simplepoint-security-oauth2-server",
    "simplepoint-security/simplepoint-security-reactor",
    "simplepoint-security/simplepoint-security-servlet",
    "simplepoint-service-router/simplepoint-service-router-consul",
    "simplepoint-service-router/simplepoint-service-router-core",
    "simplepoint-services/simplepoint-service-agent-runtime",
    "simplepoint-services/simplepoint-service-ai",
    "simplepoint-services/simplepoint-service-auditing",
    "simplepoint-services/simplepoint-service-authorization",
    "simplepoint-services/simplepoint-service-common",
    "simplepoint-services/simplepoint-service-dna",
    "simplepoint-services/simplepoint-service-host",
    "simplepoint-services/simplepoint-service-mcp-gateway",
    "simplepoint-services/simplepoint-service-workflow-runtime"
)

require(moduleDirectories.map { File(it).name }.distinct().size == moduleDirectories.size) {
    "Gradle module names must be unique"
}
moduleDirectories.forEach {
    require(file("$it/build.gradle.kts").isFile) { "Missing module build file: $it" }
}

val excludedProjects = providers.gradleProperty("excludeProjects").orNull
    ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
val enabledBuildFiles = fileTree(rootDir) {
    include(moduleDirectories.map { "$it/build.gradle.kts" })
    exclude(excludedProjects)
}.files

moduleDirectories.filter { file("$it/build.gradle.kts") in enabledBuildFiles }.forEach { directory ->
    val projectPath = ":" + directory.replace('/', ':')
    include(projectPath)
    project(projectPath).projectDir = file(directory)
}
