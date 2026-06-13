dependencies{
    api(project(":simplepoint-core"))
    api(project(":simplepoint-remoting:simplepoint-remoting-core"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation("org.springframework.security:spring-security-oauth2-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
