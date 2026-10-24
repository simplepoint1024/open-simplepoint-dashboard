import groovy.json.JsonSlurper
import org.gradle.language.jvm.tasks.ProcessResources

plugins { application }

application { mainClass.set("org.simplepoint.standalone.StandaloneApplication") }

dependencies {
    implementation(project(":simplepoint-platform:simplepoint-platform-composition-spring"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-consul"))
    implementation(project(":simplepoint-platform:simplepoint-platform-http-servlet"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap-registry"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa-composition"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))
    implementation(project(":simplepoint-events:simplepoint-events-composition"))
    implementation(project(":simplepoint-cache:simplepoint-cache-redis"))
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-server"))
    implementation(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-common-ui"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-gateway"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

// Public mounts come from the generated federation contract, shared with microservice builds.
val frontend = rootProject.file("simplepoint-react/modules")
val remotes = mapOf("simplepoint-common" to "buildCommonFrontend",
    "simplepoint-app" to "buildAppFrontend", "simplepoint-dna" to "buildDnaFrontend",
    "simplepoint-workflow" to "buildWorkflowFrontend", "simplepoint-audit" to "buildAuditFrontend",
    "simplepoint-ai" to "buildAiFrontend")
extra["aggregateFrontendExpectedRemotes"] = remotes.size

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.FAIL
    if (!providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).getOrElse(false)) {
        dependsOn(rootProject.tasks.named("buildHostFrontend"))
        from(frontend.resolve("simplepoint-host/dist")) { into("static") }
        remotes.forEach { (name, buildTask) ->
            dependsOn(rootProject.tasks.named(buildTask))
            val distribution = frontend.resolve("$name/dist/mf")
            from(distribution) {
                into(provider {
                    val document = JsonSlurper().parse(distribution.resolve("mf-manifest.json")) as Map<*, *>
                    val metadata = document["metaData"] as Map<*, *>
                    val path = metadata["publicPath"] as String
                    check(Regex("/[a-z][a-z0-9-]*/mf/").matches(path)) { "Invalid module frontend mount: $name" }
                    "static" + path.trimEnd('/')
                })
            }
        }
    }
}

val frontendIndex = layout.buildDirectory.dir("generated/frontend-index")
val generateFrontendIndex by tasks.registering {
    if (!providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).getOrElse(false)) {
        dependsOn(remotes.values.map { rootProject.tasks.named(it) })
        inputs.files(remotes.keys.map { frontend.resolve("$it/dist/mf/mf-manifest.json") })
        outputs.dir(frontendIndex)
        doLast {
            val mounts = remotes.keys.map { name ->
                val document = JsonSlurper().parse(frontend.resolve("$name/dist/mf/mf-manifest.json")) as Map<*, *>
                val path = (document["metaData"] as Map<*, *>)["publicPath"] as String
                check(Regex("/[a-z][a-z0-9-]*/mf/").matches(path)) { "Invalid frontend mount" }
                path
            }
            check(mounts.distinct().size == mounts.size) { "Duplicate frontend mount" }
            val index = frontendIndex.get().file("META-INF/simplepoint/frontend.imports").asFile
            index.parentFile.mkdirs()
            index.writeText(mounts.sorted().joinToString("\n", postfix = "\n"))
        }
    }
}
tasks.named<ProcessResources>("processResources") {
    if (!providers.gradleProperty("simplepoint.frontend.skip").map(String::toBoolean).getOrElse(false)) {
        dependsOn(generateFrontendIndex)
        from(frontendIndex)
    }
}
