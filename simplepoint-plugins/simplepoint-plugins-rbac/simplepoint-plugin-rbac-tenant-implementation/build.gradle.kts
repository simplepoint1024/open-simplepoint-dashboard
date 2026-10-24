dependencies {
    implementation(project(":simplepoint-platform:simplepoint-platform-dictionary-registration"))
    implementation(project(":simplepoint-platform:simplepoint-platform-dictionary-api"))
    implementation(project(":simplepoint-platform:simplepoint-platform-resource-api"))
    api(project(":simplepoint-core"))
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework.security:spring-security-oauth2-core")
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-data:simplepoint-data-jpa-composition"))
    testImplementation(project(":simplepoint-data:simplepoint-data-migrations"))
    testImplementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly(project(":simplepoint-events:simplepoint-events-migrations"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-module"))
}

tasks.test {
    val postgres = providers.environmentVariable("SP_DICTIONARY_TEST_POSTGRES_URL").isPresent
    inputs.property("dictionaryCatalogPostgres", postgres)
    outputs.upToDateWhen { !postgres }
    outputs.doNotCacheIf("Uses a disposable PostgreSQL dictionary catalog") { postgres }
    val entitlementPostgres = providers.environmentVariable("SP_TENANT_PACKAGE_TEST_POSTGRES_URL").isPresent
    inputs.property("tenantEntitlementPostgres", entitlementPostgres)
    outputs.upToDateWhen { !entitlementPostgres }
    outputs.doNotCacheIf("Uses an isolated PostgreSQL tenant entitlement schema") { entitlementPostgres }
}
