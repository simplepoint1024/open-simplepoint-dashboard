dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-installation"))
    api(project(":simplepoint-platform:simplepoint-platform-dictionary-registration"))
    api(project(":simplepoint-platform:simplepoint-platform-bootstrap-registry-api"))
}

tasks.register("verifyInstallationDictionaryDependencies") {
    doLast {
        val allowed = setOf("simplepoint-platform-installation", "simplepoint-platform-dictionary-registration",
            "simplepoint-platform-bootstrap-registry-api", "simplepoint-platform-dictionary-api",
            "simplepoint-platform-composition", "simplepoint-remoting-core")
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.all { it.moduleVersion.id.name in allowed }) {
            "Installation adapters must depend only on pure contracts, never frameworks or domain implementations"
        }
    }
}
tasks.named("check") { dependsOn("verifyInstallationDictionaryDependencies") }
