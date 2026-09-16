dependencies {
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-workflow-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-agent-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-skill-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-skill-implementation"))
    implementation("org.springframework:spring-tx")
    implementation("io.micrometer:micrometer-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
