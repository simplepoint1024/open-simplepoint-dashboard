dependencies {
    implementation("org.springframework:spring-jdbc")
    testRuntimeOnly("com.h2database:h2")
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("jakarta.persistence:jakarta.persistence-api")
    implementation("org.springframework:spring-tx")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
