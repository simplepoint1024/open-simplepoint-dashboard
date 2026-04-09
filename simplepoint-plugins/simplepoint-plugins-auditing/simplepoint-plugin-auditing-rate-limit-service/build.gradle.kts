dependencies {
    implementation(project(":simplepoint-core"))
    implementation("org.springframework.data:spring-data-redis")
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-api"))
}
