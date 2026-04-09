dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
