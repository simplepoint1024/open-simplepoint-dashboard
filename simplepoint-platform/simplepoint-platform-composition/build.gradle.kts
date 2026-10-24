dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-dictionary-api"))
    api(project(":simplepoint-remoting:simplepoint-remoting-core"))
}

tasks.register("verifyCompositionDependencies") {
    doLast {
        val external = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.name !in setOf("simplepoint-remoting-core", "simplepoint-platform-dictionary-api") }
        check(external.isEmpty()) {
            "Composition planning must not depend on Spring, persistence, web or transport: $external"
        }
    }
}
tasks.named("check") { dependsOn("verifyCompositionDependencies") }
