dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}"))
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testImplementation("com.zaxxer:HikariCP")
}

tasks.withType<Test>().configureEach {
    val postgres = providers.environmentVariable("PHYSICAL_OWNERSHIP_TEST_POSTGRES_URL").isPresent
    val mysql = providers.environmentVariable("PHYSICAL_OWNERSHIP_TEST_MYSQL_URL").isPresent
    inputs.property("physicalOwnershipPostgres", postgres)
    inputs.property("physicalOwnershipMysql", mysql)
    outputs.upToDateWhen { !postgres && !mysql }
    outputs.doNotCacheIf("Uses disposable physical namespace databases") { postgres || mysql }
}
