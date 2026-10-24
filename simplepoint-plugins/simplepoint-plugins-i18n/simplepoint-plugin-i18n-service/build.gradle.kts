dependencies {
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
}
