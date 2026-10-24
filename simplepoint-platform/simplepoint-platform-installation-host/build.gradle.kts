plugins { application }
application { mainClass.set("org.simplepoint.installation.host.InstallationMain") }

dependencies {
    implementation(project(":simplepoint-platform:simplepoint-platform-installation-jdbc"))
    implementation(project(":simplepoint-platform:simplepoint-platform-installation-dictionary"))
    implementation(project(":simplepoint-data:simplepoint-data-migrations"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-service"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-implementation"))
    runtimeOnly("org.postgresql:postgresql")
    implementation(project(":simplepoint-platform:simplepoint-platform-composition-spring"))
    implementation(project(":simplepoint-platform:simplepoint-platform-http-servlet"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap"))
    implementation(project(":simplepoint-platform:simplepoint-platform-bootstrap-registry"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa-composition"))
    implementation(project(":simplepoint-data:simplepoint-data-cp"))
    implementation(project(":simplepoint-events:simplepoint-events-composition"))
    implementation(project(":simplepoint-cache:simplepoint-cache-redis"))
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    implementation(project(":simplepoint-security:simplepoint-security-oauth2-server"))
    implementation(project(":simplepoint-plugin:simplepoint-plugin-webmvc"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-common-ui"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-dna:simplepoint-plugin-dna-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-http"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-gateway"))
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}

tasks.test {
    val tenantPostgres = providers.environmentVariable("SP_TENANT_PACKAGE_TEST_POSTGRES_URL").isPresent
    inputs.property("tenantAdoptionPostgres", tenantPostgres)
    outputs.upToDateWhen { !tenantPostgres }
    outputs.doNotCacheIf("Verifies offline tenant adoption against disposable PostgreSQL schemas") { tenantPostgres }
}

