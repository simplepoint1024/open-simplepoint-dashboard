dependencies {
    testApi("org.junit.jupiter:junit-jupiter")
    api("junit:junit:junit")
    api(project(":simplepoint-core"))
    api("org.springframework.boot:spring-boot-starter-actuator")
    api("org.springframework.boot:spring-boot-starter-web")
    api(project(":simplepoint-boot:simplepoint-boot-starter"))
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-starter")
}