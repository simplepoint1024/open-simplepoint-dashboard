dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework:spring-webmvc")
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-api"))
    implementation(libs.swagger.annotations)
}
