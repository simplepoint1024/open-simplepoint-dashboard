dependencies {
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-runtime-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-implementation"))
    implementation("org.springframework.data:spring-data-redis")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
