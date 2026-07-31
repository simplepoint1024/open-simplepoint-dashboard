dependencies {
    api(project(":simplepoint-core"))
    api(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-api"))

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.data:spring-data-commons")
    implementation("io.swagger.core.v3:swagger-annotations")
}
