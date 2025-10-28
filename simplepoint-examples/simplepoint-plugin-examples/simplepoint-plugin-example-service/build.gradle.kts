subprojects {
    group = "org.simplepoint.example.plugin.service"
}
dependencies {
    implementation("org.springframework:spring-core")
    implementation("org.springframework:spring-context")
    api(project(":simplepoint-examples:simplepoint-plugin-examples:simplepoint-plugin-example-service-api"))
}