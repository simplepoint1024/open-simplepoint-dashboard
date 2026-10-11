dependencies {
    implementation(project(":simplepoint-core"))
    implementation("org.springframework:spring-tx")
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-api"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
