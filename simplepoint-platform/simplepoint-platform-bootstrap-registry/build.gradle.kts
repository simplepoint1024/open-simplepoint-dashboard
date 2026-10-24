dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-bootstrap-registry-api"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
    api(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
}

tasks.withType<Test>().configureEach {
    if (System.getenv("SP_BOOTSTRAP_TEST_POSTGRES_URL") != null) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
