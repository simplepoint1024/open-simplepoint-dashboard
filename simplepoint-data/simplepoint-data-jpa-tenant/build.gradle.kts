dependencies {
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation("jakarta.servlet:jakarta.servlet-api")
    api(project(":simplepoint-data:simplepoint-data-jpa"))
    api(project(":simplepoint-data:simplepoint-data-cp"))
    api(project(":simplepoint-cloud:simplepoint-cloud-tenant"))
}