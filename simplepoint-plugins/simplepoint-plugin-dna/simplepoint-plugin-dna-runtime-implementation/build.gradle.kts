dependencies {
    runtimeOnly(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation"))
    runtimeOnly(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-implementation"))

    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))
    implementation("com.google.re2j:re2j:1.8")
    implementation(project(":simplepoint-calculation"))
    implementation(project(":simplepoint-platform:simplepoint-platform-resource-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-data:simplepoint-data-calcite:simplepoint-data-calcite-core"))
    implementation("org.apache.calcite:calcite-core:${rootProject.libs.versions.calcite.get()}")
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-tx")
    implementation("io.micrometer:micrometer-core")

    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:${rootProject.libs.versions.spring.boot.get()}"
    )

    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-implementation"))
    testImplementation(project(":simplepoint-data:simplepoint-data-migrations"))
    testRuntimeOnly(project(":simplepoint-events:simplepoint-events-migrations"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation"))
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.postgresql:postgresql")
}


tasks.test {
    systemProperty("simplepoint.dna.migrations", rootProject.file("doc/deployment/sql").absolutePath)
}

// Real database gates must run against the current disposable instance, never a cached skip.
tasks.withType<Test>().configureEach {
    val postgres = providers.environmentVariable("DNA_RUNTIME_TEST_POSTGRES_URL").isPresent
    val mysql = providers.environmentVariable("DNA_RUNTIME_TEST_MYSQL_URL").isPresent
    inputs.property("dnaRuntimePostgres", postgres)
    inputs.property("dnaRuntimeMysql", mysql)
    outputs.upToDateWhen { !postgres && !mysql }
    outputs.doNotCacheIf("Uses disposable DNA runtime databases") { postgres || mysql }
}
