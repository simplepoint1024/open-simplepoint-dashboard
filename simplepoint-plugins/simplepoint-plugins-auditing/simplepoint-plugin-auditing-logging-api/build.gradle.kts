dependencies{
    api(project(":simplepoint-core"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    api("org.springframework.security:spring-security-oauth2-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}