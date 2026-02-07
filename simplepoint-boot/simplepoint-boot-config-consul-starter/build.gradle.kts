dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    api(project(":simplepoint-boot:simplepoint-boot-starter"))
    api("org.springframework.cloud:spring-cloud-starter-consul-discovery")
    api("org.springframework.cloud:spring-cloud-starter-consul-config")
    api("org.springframework.cloud:spring-cloud-starter-loadbalancer")
}