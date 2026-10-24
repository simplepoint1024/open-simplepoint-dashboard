dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}"))
    api(project(":simplepoint-platform:simplepoint-platform-installation"))
    api(project(":simplepoint-data:simplepoint-data-migrations-api"))
    testImplementation(project(":simplepoint-data:simplepoint-data-migrations"))
    testImplementation("org.springframework:spring-jdbc")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.h2database:h2")
}

tasks.withType<Test>().configureEach {
    val postgres = providers.environmentVariable("SP_INSTALLATION_TEST_POSTGRES_URL").isPresent
    inputs.property("installationPostgres", postgres)
    outputs.upToDateWhen { !postgres }
    outputs.doNotCacheIf("Uses a disposable installation journal database") { postgres }
}

tasks.register("verifyInstallationJdbcDependencies") {
    doLast {
        val allowed = setOf("simplepoint-platform-installation", "simplepoint-data-migrations-api", "simplepoint-platform-composition", "simplepoint-platform-dictionary-api", "simplepoint-remoting-core")
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.all { it.moduleVersion.id.name in allowed }) {
            "Installation JDBC storage must not inherit frameworks or business implementations"
        }
    }
}
tasks.named("check") { dependsOn("verifyInstallationJdbcDependencies") }
