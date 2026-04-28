dependencies {
    implementation("org.springframework.data:spring-data-redis")
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-api"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
