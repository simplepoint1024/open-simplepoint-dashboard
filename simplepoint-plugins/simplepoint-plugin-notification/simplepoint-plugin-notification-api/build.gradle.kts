dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-dictionary-api"))
    api(project(":simplepoint-core"))
    api(project(":simplepoint-remoting:simplepoint-remoting-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.swagger.annotations)
}
