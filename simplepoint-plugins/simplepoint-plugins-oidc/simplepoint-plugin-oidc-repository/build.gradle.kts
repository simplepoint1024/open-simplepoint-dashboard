dependencies {
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
}