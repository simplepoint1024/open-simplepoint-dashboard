tasks.register("verifyInstallationCoreDependencies") {
    doLast {
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.isEmpty()) {
            "Installation orchestration must remain a Java-only library"
        }
    }
}
tasks.named("check") { dependsOn("verifyInstallationCoreDependencies") }
