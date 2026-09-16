dependencies {
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
