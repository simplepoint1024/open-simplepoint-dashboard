plugins{
    id("org.springframework.boot") version libs.versions.spring.boot.get() apply false
}
dependencies {

    implementation(project(":simplepoint-boot:simplepoint-boot-starter"))
    // 引入Consul全局配置支持
    implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))
    implementation(project(":simplepoint-boot:simplepoint-boot-config-vault-starter"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")

    implementation(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-server"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // 加载路由菜单相关依赖，确保
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-repository"))
}