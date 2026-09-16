dependencies {
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-api"))
    implementation(project(":simplepoint-core"))
    implementation("org.springframework.data:spring-data-redis")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation(project(":simplepoint-security:simplepoint-security-core"))
}
