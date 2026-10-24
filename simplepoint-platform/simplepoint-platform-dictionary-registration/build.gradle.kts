dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-dictionary-api"))
    api(project(":simplepoint-platform:simplepoint-platform-composition"))
    api(project(":simplepoint-remoting:simplepoint-remoting-core"))
}

tasks.register("verifyDictionaryRegistrationDependencies") {
    doLast {
        val allowed = setOf("simplepoint-platform-dictionary-api", "simplepoint-platform-composition", "simplepoint-remoting-core")
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.all { it.moduleVersion.id.name in allowed }) {
            "Dictionary contracts and initialization must remain free of frameworks and persistence"
        }
    }
}
tasks.named("check") { dependsOn("verifyDictionaryRegistrationDependencies") }
