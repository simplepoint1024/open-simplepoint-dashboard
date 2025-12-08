dependencies {
    api(project(":simplepoint-security:simplepoint-security-core"))
    api(project(":simplepoint-data:simplepoint-data-cp"))
    api(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation("org.springframework:spring-core")
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-repository"))
    implementation(libs.spring.authorization.server)
}