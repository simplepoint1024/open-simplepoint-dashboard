subprojects {
    group = "org.simplepoint.example.plugin.controller"
}
dependencies {
    implementation("org.springframework:spring-core")
    implementation("org.springframework:spring-web")
    implementation(project(":simplepoint-examples:simplepoint-plugin-examples:simplepoint-plugin-example-service-api"))
}