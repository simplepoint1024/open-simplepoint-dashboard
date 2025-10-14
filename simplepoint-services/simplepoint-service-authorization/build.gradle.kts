dependencies {
    api(project(":simplepoint-core"))
    api(project(":simplepoint-boot:simplepoint-boot-starter-web"))
    api(project(":simplepoint-cloud:simplepoint-cloud-consul"))
    api(project(":simplepoint-cloud:simplepoint-cloud-loadbalancer"))
    api(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    api(project(":simplepoint-data:simplepoint-data-cp"))
    api(project(":simplepoint-data:simplepoint-data-jpa"))
    api(project(":simplepoint-data:simplepoint-data-redis"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    api("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-server"))
}