dependencies {
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-core"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    // Every runtime hosting this implementation must validate tenant membership and organizations.
    // tenant-implementation depends on core-api only, so this does not form a project cycle.
    runtimeOnly(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation("org.aspectj:aspectjweaver")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}
