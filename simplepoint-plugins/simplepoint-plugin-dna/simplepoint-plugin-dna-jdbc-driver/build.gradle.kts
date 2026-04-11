import java.io.File
import java.util.jar.JarFile
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.the

dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

val sourceSets = the<SourceSetContainer>()
val mainOutput = sourceSets.named("main").get().output
val standaloneServicesDir = layout.buildDirectory.dir("generated/standalone-services")

val generateStandaloneServiceDescriptors = tasks.register("generateStandaloneServiceDescriptors") {
    inputs.files(mainOutput, configurations.runtimeClasspath)
    outputs.dir(standaloneServicesDir)

    doLast {
        val outputDir = standaloneServicesDir.get().asFile
        project.delete(outputDir)

        val descriptors = linkedMapOf<String, LinkedHashSet<String>>()

        fun collectDescriptor(path: String, content: String) {
            val providers = content.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            if (providers.isEmpty()) {
                return
            }
            descriptors.getOrPut(path) { linkedSetOf() }.addAll(providers)
        }

        fun collectFromEntry(entry: File) {
            if (!entry.exists()) {
                return
            }
            if (entry.isDirectory) {
                entry.walkTopDown()
                    .filter(File::isFile)
                    .forEach { file ->
                        val relativePath = file.relativeTo(entry).path.replace(File.separatorChar, '/')
                        if (relativePath.startsWith("META-INF/services/")) {
                            collectDescriptor(relativePath, file.readText())
                        }
                    }
                return
            }
            if (entry.extension != "jar") {
                return
            }
            JarFile(entry).use { jar ->
                val jarEntries = jar.entries()
                while (jarEntries.hasMoreElements()) {
                    val jarEntry = jarEntries.nextElement()
                    if (jarEntry.isDirectory || !jarEntry.name.startsWith("META-INF/services/")) {
                        continue
                    }
                    val content = jar.getInputStream(jarEntry).bufferedReader().use { it.readText() }
                    collectDescriptor(jarEntry.name, content)
                }
            }
        }

        mainOutput.files.forEach(::collectFromEntry)
        configurations.runtimeClasspath.get().files.forEach(::collectFromEntry)

        descriptors.forEach { (path, providers) ->
            val target = outputDir.resolve(path)
            target.parentFile.mkdirs()
            target.writeText(
                providers.joinToString(
                    separator = System.lineSeparator(),
                    postfix = System.lineSeparator()
                )
            )
        }
    }
}

val standaloneJar = tasks.register<Jar>("standaloneJar") {
    description = "Builds a standalone distributable DNA JDBC driver jar with runtime dependencies."
    group = "build"
    archiveClassifier.set("standalone")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    dependsOn(generateStandaloneServiceDescriptors)

    from(mainOutput) {
        exclude("META-INF/services/**")
    }
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.extension == "jar" }
            .map(::zipTree)
    }) {
        exclude(
            "META-INF/MANIFEST.MF",
            "META-INF/INDEX.LIST",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/services/**"
        )
    }
    from(standaloneServicesDir)

    manifest {
        attributes(
            "Implementation-Title" to "SimplePoint DNA JDBC Driver",
            "Implementation-Version" to project.version.toString()
        )
    }
}

tasks.named("assemble") {
    dependsOn(standaloneJar)
}
