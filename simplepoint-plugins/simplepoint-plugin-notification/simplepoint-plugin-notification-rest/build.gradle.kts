dependencies {
    implementation(project(":simplepoint-core"))
    implementation("jakarta.servlet:jakarta.servlet-api")
    implementation("org.springframework.data:spring-data-redis")
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework:spring-tx")
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-api"))
    implementation(libs.swagger.annotations)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
