dependencies {
    api(libs.oauth2.oidc.sdk)
    api(project(":simplepoint-core"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
}