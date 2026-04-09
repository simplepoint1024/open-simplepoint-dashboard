dependencies {
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.security:spring-security-core")
    implementation("org.slf4j:slf4j-api")
    implementation("ch.qos.logback:logback-classic")
    api(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
}
