dependencies {
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    api(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-implementation"))
    implementation(project(":simplepoint-platform:simplepoint-platform-http-servlet"))
    implementation(project(":simplepoint-core"))
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework.security:spring-security-core")
    implementation(libs.swagger.annotations)
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-config")
}
