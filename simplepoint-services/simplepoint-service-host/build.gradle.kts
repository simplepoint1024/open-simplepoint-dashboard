dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-boot:simplepoint-boot-starter-webflux"))
    implementation(project(":simplepoint-cloud:simplepoint-cloud-consul"))
    implementation(project(":simplepoint-cloud:simplepoint-cloud-loadbalancer"))
    implementation("org.springframework.cloud:spring-cloud-gateway-server-webflux")
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-client"))
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
//    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.session:spring-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    api(project(":simplepoint-data:simplepoint-data-amqp:simplepoint-data-amqp-rpc"))

    api(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))

    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui")
}