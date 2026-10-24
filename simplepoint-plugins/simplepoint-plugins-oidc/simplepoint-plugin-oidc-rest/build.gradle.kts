dependencies {
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    api(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-module"))
    implementation(project(":simplepoint-platform:simplepoint-platform-http-servlet"))
    implementation(libs.swagger.annotations)
}
