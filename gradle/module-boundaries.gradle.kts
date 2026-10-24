import java.util.zip.ZipFile

// Verify packaged classes as well as Gradle dependencies: shared implementation
// modules must not introduce management endpoints into authentication or workers.
val serviceBoundaries = mapOf(
    "simplepoint-service-authorization" to listOf(
        "org/simplepoint/plugin/rbac/.*/rest/.*\\.class",
        "org/simplepoint/plugin/rbac/credentials/.*\\.class"
    ),
    "simplepoint-service-host" to listOf("org/simplepoint/plugin/auditing/logging/(repository|rest|service)/.*\\.class"),
    "simplepoint-service-dna" to listOf("org/simplepoint/plugin/auditing/logging/(repository|rest|service)/.*\\.class")
)

val ownedWorkflowEngines = listOf(
    ":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-implementation",
    ":simplepoint-services:simplepoint-service-workflow"
)

val migrationMetadataConsumers = listOf(
    ":simplepoint-data:simplepoint-data-migrations-api",
    ":simplepoint-events:simplepoint-events-migrations",
    ":simplepoint-events:simplepoint-events-composition",
    ":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-migrations"
) + ownedWorkflowEngines

val optionalHttpAdapters = mapOf(
    ":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-implementation" to
        ":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-http",
    ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http",
    ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-catalog-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http",
    ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http",
    ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http",
    ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-runtime-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http",

    ":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-implementation" to
        ":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-http",
    ":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-implementation" to
        ":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-http",
    ":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-implementation" to
        ":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-http",

    ":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-http",
    ":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-http",
    ":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-http",

    ":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-form-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-http",
    ":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-module" to
        ":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-rest",
    ":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-module" to
        ":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-rest",
    ":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-module" to
        ":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-http",
    ":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-implementation" to
        ":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-http",
    ":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-module" to
        ":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-rest",
    ":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-module" to
        ":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-rest"
)

val verifyModuleBoundaries = tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Checks service isolation after consolidating domain modules."
    dependsOn(":simplepoint-platform:simplepoint-platform-bootstrap-registry-api:verifyBootstrapRegistryApiDependencies")
    dependsOn(":simplepoint-platform:simplepoint-platform-installation-dictionary:verifyInstallationDictionaryDependencies")
    serviceBoundaries.keys.forEach { name ->
        dependsOn(project(":simplepoint-services:$name").configurations.named("runtimeClasspath"))
    }
    dependsOn(project(":simplepoint-data:simplepoint-data-migrations").configurations.named("runtimeClasspath"))
    dependsOn(project(":simplepoint-events:simplepoint-events-migrations").configurations.named("runtimeClasspath"))
    dependsOn(project(":simplepoint-events:simplepoint-events-api").configurations.named("runtimeClasspath"))
    dependsOn(project(":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-migrations").configurations.named("runtimeClasspath"))
    ownedWorkflowEngines.forEach { path ->
        dependsOn(project(path).configurations.named("runtimeClasspath"))
    }
    migrationMetadataConsumers.forEach { path ->
        dependsOn(project(path).configurations.named("runtimeClasspath"))
    }
    optionalHttpAdapters.forEach { (domain, adapter) ->
        dependsOn(project(domain).configurations.named("runtimeClasspath"))
        dependsOn(project(adapter).configurations.named("runtimeClasspath"))
    }
    doLast {
        val httpArtifactNames = optionalHttpAdapters.values.map { project(it).name }.toSet()
        optionalHttpAdapters.forEach { (domain, adapter) ->
            val domainRuntime = project(domain).configurations.getByName("runtimeClasspath")
                .resolvedConfiguration.resolvedArtifacts
            check(domainRuntime.none { it.moduleVersion.id.name in httpArtifactNames }) {
                "$domain must not depend on an optional HTTP adapter"
            }
            val httpRuntime = project(adapter).configurations.getByName("runtimeClasspath")
                .resolvedConfiguration.resolvedArtifacts
            check(httpRuntime.none { it.moduleVersion.id.name.startsWith("simplepoint-service-")
                && !it.moduleVersion.id.name.startsWith("simplepoint-service-router-") }) {
                "$adapter must not load a runnable service host"
            }
        }
        listOf(
            ":simplepoint-data:simplepoint-data-migrations",
            ":simplepoint-data:simplepoint-data-migrations-api",
            ":simplepoint-events:simplepoint-events-api",
            ":simplepoint-events:simplepoint-events-migrations",
            ":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-migrations"
        ).forEach { migrationProject ->
            val migrationRuntime = project(migrationProject)
                .configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
            check(migrationRuntime.none {
                it.moduleVersion.id.group.startsWith("org.springframework")
                    || it.moduleVersion.id.group.startsWith("org.hibernate")
                    || it.moduleVersion.id.group == "jakarta.persistence"
                    || it.moduleVersion.id.group.startsWith("com.rabbitmq")
                    || it.moduleVersion.id.group == "org.flowable"
                    || it.moduleVersion.id.name.endsWith("-implementation")
            }) { "$migrationProject must not load Spring, JPA, broker clients, native engines or domain implementations" }
        }
        ownedWorkflowEngines.forEach { path ->
            val workflowRuntime = project(path)
                .configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
            check(workflowRuntime.none {
                it.moduleVersion.id.group == "org.flowable"
                    && it.moduleVersion.id.name.startsWith("flowable-spring-boot")
            }) {
                "$path must use the owned engine factory without Flowable Boot auto-configuration"
            }
        }
        migrationMetadataConsumers.forEach { path ->
            val runtime = project(path)
                .configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
            check(runtime.none {
                it.moduleVersion.id.group == "org.flywaydb"
                    || it.moduleVersion.id.name == "simplepoint-data-migrations"
            }) { "$path must not load the migration executor or trigger Boot's default Flyway migration" }
        }
        serviceBoundaries.forEach { (name, forbiddenClasses) ->
            val patterns = forbiddenClasses.map(::Regex)
            val jars = project(":simplepoint-services:$name").configurations.getByName("runtimeClasspath")
                .files.filter { it.extension == "jar" }
            jars.forEach { jar ->
                ZipFile(jar).use { archive ->
                    val unexpected = archive.entries().asSequence()
                        .map { it.name }.firstOrNull { entry -> patterns.any { it.matches(entry) } }
                    check(unexpected == null) { "$name unexpectedly loads $unexpected from ${jar.name}" }
                }
            }
        }
        val gateway = project(":simplepoint-services:simplepoint-service-mcp-gateway")
            .configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
        val forbiddenGroups = setOf("org.postgresql", "mysql", "com.mysql")
        check(gateway.none {
            it.moduleVersion.id.group in forbiddenGroups
                || it.moduleVersion.id.name.endsWith("-implementation")
        }) {
            "MCP Gateway must not load domain implementations or database drivers"
        }
        logger.lifecycle("Service module boundaries verified")
    }
}

tasks.named("check") {
    dependsOn(verifyModuleBoundaries)
}
