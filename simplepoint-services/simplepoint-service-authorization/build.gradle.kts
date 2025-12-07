dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-cache"))
    implementation(project(":simplepoint-boot:simplepoint-boot-starter-web"))
    implementation(project(":simplepoint-cloud:simplepoint-cloud-consul"))
    implementation(project(":simplepoint-cloud:simplepoint-cloud-loadbalancer"))
    implementation(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
//    implementation(project(":simplepoint-data:simplepoint-data-jpa-tenant"))
    implementation(project(":simplepoint-data:simplepoint-data-redis"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-server"))

    // 加载路由菜单相关依赖，确保
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-repository"))
}