dependencies {
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-authentication"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation(project(":simplepoint-events:simplepoint-events-api"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-events:simplepoint-events-jdbc"))
    testImplementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    testRuntimeOnly("com.h2database:h2")
}
