dependencies {
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    implementation(project(":simplepoint-platform:simplepoint-platform-dictionary-registration"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-module"))
    implementation(project(":simplepoint-platform:simplepoint-platform-http-servlet"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework.security:spring-security-core")
    implementation(libs.swagger.annotations)
}
