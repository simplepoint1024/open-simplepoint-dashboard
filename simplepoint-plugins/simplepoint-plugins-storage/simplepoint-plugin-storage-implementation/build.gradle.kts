dependencies {
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    runtimeOnly(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-module"))
    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))

    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.aws.sdk.s3)
}
