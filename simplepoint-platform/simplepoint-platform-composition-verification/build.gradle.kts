// Architecture tests use real domain artifacts without importing runnable service applications.
dependencies {
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-form-implementation"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-implementation"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-runtime-api"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-implementation"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-core-api"))
    testImplementation(project(":simplepoint-data:simplepoint-data-ownership"))
    testImplementation(project(":simplepoint-data:simplepoint-data-cp"))
    testImplementation(project(":simplepoint-data:simplepoint-data-calcite:simplepoint-data-calcite-core"))
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    testRuntimeOnly(project(":simplepoint-security:simplepoint-security-oauth2-server"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-common-ui"))
    testImplementation(project(":simplepoint-platform:simplepoint-platform-installation-jdbc"))
    testImplementation(project(":simplepoint-platform:simplepoint-platform-installation-dictionary"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-implementation"))
    testImplementation("org.springframework.amqp:spring-rabbit")
    testImplementation(libs.spring.authorization.server)
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-implementation"))
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":simplepoint-data:simplepoint-data-migrations"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-migrations"))
    testImplementation(platform("org.flowable:flowable-bom:${rootProject.libs.versions.flowable.get()}"))
    testImplementation("org.flowable:flowable-engine")
    testImplementation("org.flowable:flowable-spring")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("com.h2database:h2")
    testImplementation(project(":simplepoint-data:simplepoint-data-jpa-composition"))
    testImplementation(project(":simplepoint-events:simplepoint-events-composition"))
    testImplementation(project(":simplepoint-events:simplepoint-events-migrations"))
    testImplementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-api"))
    testImplementation(project(":simplepoint-platform:simplepoint-platform-composition"))
    testImplementation(project(":simplepoint-platform:simplepoint-platform-bootstrap-registry"))
    testImplementation("org.springframework:spring-context")
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-gateway"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-implementation"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-implementation"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-knowledge-api"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-core-api"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-implementation"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-runtime-implementation"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-http"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-federation-implementation"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-http"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-rest"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-personal:simplepoint-plugin-personal-rest"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-implementation"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-http"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-http"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-rate-limit-implementation"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-redis-implementation"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugins-i18n:simplepoint-plugin-i18n-rest"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-rest"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-http"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugins-storage:simplepoint-plugin-storage-http"))
}

// Real schema verification must execute against the current disposable database, never a cached skip.
tasks.withType<Test>().configureEach {
    val postgres = providers.environmentVariable("SP_MIGRATIONS_POSTGRES_URL").isPresent
    inputs.property("moduleMigrationPostgres", postgres)
    outputs.upToDateWhen { !postgres }
    outputs.doNotCacheIf("Uses a disposable PostgreSQL migration database") { postgres }
    val relations = providers.environmentVariable("DNA_RUNTIME_TEST_POSTGRES_URL").isPresent
    inputs.property("formRelationDatabase", relations)
    outputs.upToDateWhen { !relations }
    outputs.doNotCacheIf("Uses disposable form relation databases") { relations }
}

tasks.test {
    systemProperty("simplepoint.dna.legacy-sql", rootProject.file("doc/deployment/sql").absolutePath)
    systemProperty("simplepoint.ai.legacy-sql", rootProject.file("simplepoint-services/simplepoint-service-ai/src/main/resources/schema.sql").absolutePath)
}
