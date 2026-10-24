dependencies {
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    api(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-implementation"))
    api(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-implementation"))
    api(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-api"))
    api(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-implementation"))
    implementation(project(":simplepoint-platform:simplepoint-platform-http-servlet"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    implementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    implementation(project(":simplepoint-core"))
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.security:spring-security-core")
    implementation(libs.swagger.annotations)
    testImplementation(project(":simplepoint-data:simplepoint-data-jpa"))
    testImplementation(project(":simplepoint-data:simplepoint-data-json:simplepoint-data-json-schema"))
    testImplementation("org.springframework.data:spring-data-redis")
    testImplementation("io.lettuce:lettuce-core")
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.security:spring-security-config")
    testImplementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
}

// Test fixtures follow the repository root when the reusable adapter moves between hosts.
tasks.test {
    systemProperty("simplepoint.redis.query-schema", rootProject.file("simplepoint-react/scripts/redis-schema.json").absolutePath)
    val redis = providers.environmentVariable("REDIS_QUERY_TEST_PORT").isPresent
    inputs.property("auditingHttpRedis", redis)
    outputs.upToDateWhen { !redis }
    outputs.doNotCacheIf("Uses a disposable Redis instance") { redis }
}
