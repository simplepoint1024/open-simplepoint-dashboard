dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    api(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}