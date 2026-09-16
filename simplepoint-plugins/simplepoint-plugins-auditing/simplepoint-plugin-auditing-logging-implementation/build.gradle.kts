dependencies {
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation(libs.swagger.annotations)
}
