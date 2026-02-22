dependencies{
    api(project(":simplepoint-core"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework.security:spring-security-oauth2-core")
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
}