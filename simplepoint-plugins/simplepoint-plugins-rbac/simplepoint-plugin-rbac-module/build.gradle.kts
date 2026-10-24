dependencies {
    api(project(":simplepoint-platform:simplepoint-platform-dictionary-registration"))
    implementation("io.micrometer:micrometer-core")
    implementation(project(":simplepoint-events:simplepoint-events-api"))
    implementation(project(":simplepoint-platform:simplepoint-platform-resource-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-authentication"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-credentials"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))

    implementation(project(":simplepoint-platform:simplepoint-platform-composition"))

    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-implementation"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-implementation"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-implementation"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-repository"))
}
