dependencies {
    testApi("org.junit.jupiter:junit-jupiter")
    api("junit:junit:junit")
    api(project(":simplepoint-core"))
    api(project(":simplepoint-boot:simplepoint-boot-starter"))
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.boot:spring-boot-starter-webflux")
}