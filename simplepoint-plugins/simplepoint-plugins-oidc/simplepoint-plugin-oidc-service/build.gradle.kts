dependencies {
    api(project(":simplepoint-data:simplepoint-data-cp"))
    api(project(":simplepoint-data:simplepoint-data-jpa"))
    api(project(":simplepoint-data:simplepoint-data-redis"))
    implementation("org.springframework:spring-core")
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-base"))
    implementation(libs.spring.authorization.server)
}