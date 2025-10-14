dependencies {
    api("org.springframework.boot:spring-boot-starter-oauth2-client")
    api ("org.springframework.boot:spring-boot-starter-security")
    api(project(":simplepoint-security:simplepoint-security-core"))
    api(project(":simplepoint-core"))
    api("org.springframework.boot:spring-boot-starter-webflux")
}