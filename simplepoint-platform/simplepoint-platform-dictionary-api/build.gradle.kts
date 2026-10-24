dependencies {
    testImplementation(platform("com.fasterxml.jackson:jackson-bom:${rootProject.libs.versions.jackson.get()}"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
}

tasks.register("verifyDictionaryApiDependencies") {
    doLast {
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.isEmpty()) {
            "Dictionary declarations must not depend on frameworks, persistence or domain implementations"
        }
    }
}
tasks.named("check") { dependsOn("verifyDictionaryApiDependencies") }
