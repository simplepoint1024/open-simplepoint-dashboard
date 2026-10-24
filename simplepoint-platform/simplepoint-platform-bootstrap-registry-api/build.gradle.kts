tasks.register("verifyBootstrapRegistryApiDependencies") {
    doLast {
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.isEmpty()) {
            "Bootstrap installation contracts must remain Java-only"
        }
    }
}
tasks.named("check") { dependsOn("verifyBootstrapRegistryApiDependencies") }
