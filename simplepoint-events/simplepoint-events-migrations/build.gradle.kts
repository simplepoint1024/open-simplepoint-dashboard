dependencies {
    api(project(":simplepoint-data:simplepoint-data-migrations-api"))
    implementation(project(":simplepoint-events:simplepoint-events-api"))
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.spring.boot.get()}"))
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework:spring-jdbc")
    testImplementation(project(":simplepoint-data:simplepoint-data-migrations"))
    testRuntimeOnly(project(":simplepoint-events:simplepoint-events-jdbc"))
    testRuntimeOnly("com.h2database:h2")
}
