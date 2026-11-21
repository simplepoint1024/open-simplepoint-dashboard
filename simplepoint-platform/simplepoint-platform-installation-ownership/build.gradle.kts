dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-installation"))
    api(project(":simplepoint-data:simplepoint-data-ownership"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}"))
    testImplementation(project(":simplepoint-platform:simplepoint-platform-installation-jdbc"))
    testImplementation(project(":simplepoint-data:simplepoint-data-migrations"))
    testImplementation("org.springframework:spring-jdbc")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.mysql:mysql-connector-j")
}

tasks.withType<Test>().configureEach {
    val postgres = providers.environmentVariable("PHYSICAL_OWNERSHIP_TEST_POSTGRES_URL").isPresent
    val mysql = providers.environmentVariable("PHYSICAL_OWNERSHIP_TEST_MYSQL_URL").isPresent
    inputs.property("ownershipInstallationPostgres", postgres)
    inputs.property("ownershipInstallationMysql", mysql)
    outputs.upToDateWhen { !postgres && !mysql }
    outputs.doNotCacheIf("Uses disposable native namespace installation targets") { postgres || mysql }
}

tasks.register("verifyInstallationOwnershipDependencies") {
    doLast {
        val allowed = setOf("simplepoint-platform-installation", "simplepoint-data-ownership")
        check(configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.all {
            it.moduleVersion.id.name in allowed
        }) {
            "Ownership installation adapters must remain JDK/JDBC-only, without domain runtimes or frameworks"
        }
    }
}
tasks.named("check") { dependsOn("verifyInstallationOwnershipDependencies") }
