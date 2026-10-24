dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-composition"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
