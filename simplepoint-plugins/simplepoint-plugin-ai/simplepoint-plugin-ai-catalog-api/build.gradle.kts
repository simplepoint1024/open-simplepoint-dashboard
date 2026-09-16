dependencies {
    api(project(":simplepoint-core"))
    api(project(":simplepoint-data:simplepoint-data-cp"))
    api(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-api"))
    api(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-api"))
    api(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-runtime-api"))
    implementation(libs.swagger.annotations)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
