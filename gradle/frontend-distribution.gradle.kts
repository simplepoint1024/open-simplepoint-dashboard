import groovy.json.JsonSlurper
import java.util.jar.JarFile
import org.gradle.jvm.tasks.Jar

// Backend tests may omit frontend resources. Runnable distributions must contain their actual assets.
val serviceJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val skipFrontend = providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).orElse(false)
val aggregateFrontend = project.name == "simplepoint-service-standalone"
val hostFrontend = project.name == "simplepoint-service-host" || aggregateFrontend

val verifyFrontendDistribution = tasks.register("verifyFrontendDistribution") {
    group = "verification"
    description = "Checks the packaged frontend entry and every declared JavaScript/CSS asset."
    dependsOn(tasks.named("jar"))
    inputs.file(serviceJar)
    inputs.property("skipFrontend", skipFrontend)
    doLast {
        check(!skipFrontend.get()) {
            "simplepoint.frontend.skip is for backend tests only; distributions require bundled frontend assets."
        }
        JarFile(serviceJar.get().asFile).use { jar ->
            fun requireAsset(name: String) {
                val entry = jar.getJarEntry(name)
                check(entry != null && !entry.isDirectory && entry.size > 0) {
                    "Packaged frontend asset is missing or empty: $name in ${jar.name}"
                }
            }
            if (hostFrontend) {
                requireAsset("static/index.html")
                val html = jar.getInputStream(jar.getJarEntry("static/index.html")).bufferedReader().use { it.readText() }
                val assets = Regex("(?:src|href)=[\"']/?(assets/[^\"']+)[\"']")
                    .findAll(html).map { it.groupValues[1] }.toSet()
                check(assets.isNotEmpty()) { "Host HTML declares no packaged assets" }
                assets.forEach { requireAsset("static/$it") }
            }
            if (!hostFrontend || aggregateFrontend) {
                val manifests = if (aggregateFrontend) {
                    val found = jar.entries().asSequence().map { it.name }
                        .filter { Regex("static/[a-z][a-z0-9-]*/mf/mf-manifest.json").matches(it) }.toList()
                    check(found.size == project.extra["aggregateFrontendExpectedRemotes"] as Int) {
                        "Aggregate distribution does not contain all selected frontend contributions"
                    }
                    found
                } else listOf("static/mf/mf-manifest.json")
                manifests.forEach { manifest ->
                val assetRoot = manifest.removeSuffix("mf-manifest.json")
                requireAsset(manifest)
                val document = jar.getInputStream(jar.getJarEntry(manifest)).use {
                    JsonSlurper().parse(it) as Map<*, *>
                }
                val metadata = document["metaData"] as Map<*, *>
                val remote = metadata["remoteEntry"] as Map<*, *>
                val remoteName = remote["name"] as String
                check(remoteName.isNotBlank()) { "Federation manifest declares no remote entry" }
                requireAsset("${assetRoot}${remote["path"] ?: ""}$remoteName")
                fun names(value: Any?): List<String> = when (value) {
                    is Map<*, *> -> value.values.flatMap { names(it) }
                    is List<*> -> value.flatMap { names(it) }
                    is String -> listOf(value)
                    else -> emptyList()
                }
                val assets = listOf("exposes", "shared").flatMap { section ->
                    (document[section] as? List<*>)?.flatMap { names((it as Map<*, *>)["assets"]) } ?: emptyList()
                }.toSet()
                check(assets.isNotEmpty()) { "Federation manifest declares no packaged assets" }
                assets.forEach { requireAsset("$assetRoot$it") }
                if (aggregateFrontend) {
                    check(metadata["publicPath"] == "/" + assetRoot.removePrefix("static/")) {
                        "Federation public URL differs from the packaged mount: $manifest"
                    }
                }
                }
            }
        }
    }
}

tasks.matching { it.name in setOf("installDist", "distZip", "distTar") }.configureEach {
    dependsOn(verifyFrontendDistribution)
}
