import java.util.zip.ZipFile

// Verify packaged classes as well as Gradle dependencies: shared implementation
// modules must not introduce management endpoints into authentication or workers.
val serviceBoundaries = mapOf(
    "simplepoint-service-agent-runtime" to listOf("org/simplepoint/plugin/ai/.*/rest/.*\\.class"),
    "simplepoint-service-workflow-runtime" to listOf("org/simplepoint/plugin/ai/.*/rest/.*\\.class"),
    "simplepoint-service-authorization" to listOf("org/simplepoint/plugin/rbac/.*/rest/.*\\.class"),
    "simplepoint-service-host" to listOf("org/simplepoint/plugin/auditing/logging/(repository|rest|service)/.*\\.class")
)

val verifyModuleBoundaries = tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Checks service isolation after consolidating domain modules."
    serviceBoundaries.keys.forEach { name ->
        dependsOn(project(":simplepoint-services:$name").configurations.named("runtimeClasspath"))
    }
    doLast {
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
