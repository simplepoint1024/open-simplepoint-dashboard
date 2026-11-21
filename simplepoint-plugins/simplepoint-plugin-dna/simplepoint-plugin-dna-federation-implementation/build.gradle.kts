dependencies {
    runtimeOnly(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation"))

    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))

    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.apache.calcite:calcite-core:${rootProject.libs.versions.calcite.get()}")
    // Parse native catalog expressions (for example PostgreSQL :: casts) with the same Calcite release.
    implementation("org.apache.calcite:calcite-babel:${rootProject.libs.versions.calcite.get()}")
    implementation(project(":simplepoint-data:simplepoint-data-calcite:simplepoint-data-calcite-core"))
    implementation(project(":simplepoint-data:simplepoint-data-ownership"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    compileOnly("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.h2database:h2")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.postgresql:postgresql")
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation"))
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    implementation(libs.swagger.annotations)
}

// Live database validation must not reuse a cached result from a different disposable instance.
tasks.withType<Test>().configureEach {
    val postgres = providers.environmentVariable("DNA_FEDERATION_TEST_POSTGRES_URL").isPresent
    val mysql = providers.environmentVariable("DNA_FEDERATION_TEST_MYSQL_URL").isPresent
    inputs.property("dnaFederationPostgres", postgres)
    inputs.property("dnaFederationMysql", mysql)
    outputs.upToDateWhen { !postgres && !mysql }
    outputs.doNotCacheIf("Uses disposable federation databases") { postgres || mysql }
}
