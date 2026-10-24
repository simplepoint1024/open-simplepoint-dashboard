dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}"))
    api(project(":simplepoint-data:simplepoint-data-migrations-api"))
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation("com.h2database:h2")
    testImplementation("org.postgresql:postgresql")
}

tasks.test {
    // Switching from skipped to real PostgreSQL verification must invalidate the task cache.
    inputs.property("postgresAdoptionUrl", providers.environmentVariable("SP_ADOPTION_TEST_URL").orElse(""))
    val postgres = providers.environmentVariable("SP_ADOPTION_TEST_URL").isPresent
    outputs.upToDateWhen { !postgres }
    outputs.doNotCacheIf("Verifies a real PostgreSQL adoption database") { postgres }
}
