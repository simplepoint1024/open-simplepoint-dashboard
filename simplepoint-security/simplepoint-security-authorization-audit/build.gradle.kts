dependencies {
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))
    implementation(project(":simplepoint-events:simplepoint-events-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-events:simplepoint-events-jdbc"))
    testRuntimeOnly("com.h2database:h2")
}
