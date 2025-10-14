dependencies {
    implementation(project(":simplepoint-core"))
    implementation("org.springframework:spring-core")
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.cloud:spring-cloud-starter-consul-discovery")
}