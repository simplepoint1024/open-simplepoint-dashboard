dependencies {
    api("org.springframework.boot:spring-boot")
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-context")
    api("org.springframework:spring-web")
    api("org.springframework.cloud:spring-cloud-commons")
    api(project(":simplepoint-remoting:simplepoint-remoting-core"))
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
