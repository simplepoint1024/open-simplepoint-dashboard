dependencies {
    api(project(":simplepoint-core"))
    api(project(":simplepoint-data:simplepoint-data-cp"))
    api(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-api"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.swagger.annotations)
}
