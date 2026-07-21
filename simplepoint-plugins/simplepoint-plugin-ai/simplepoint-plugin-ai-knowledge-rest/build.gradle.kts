dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-api"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.swagger.annotations)
}
