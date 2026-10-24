dependencies {
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-http"))
    api(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-rest"))
    api(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-rest"))
    api(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-rest"))
    api(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-rest"))
    api(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-http"))
    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    testImplementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
