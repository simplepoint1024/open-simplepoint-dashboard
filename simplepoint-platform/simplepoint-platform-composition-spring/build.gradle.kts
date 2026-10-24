dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-composition"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    testImplementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
}
