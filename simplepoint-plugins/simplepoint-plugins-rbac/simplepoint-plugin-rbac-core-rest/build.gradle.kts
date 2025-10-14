dependencies {
    implementation(project(":simplepoint-core"))
    implementation(libs.swagger.annotations)
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
}