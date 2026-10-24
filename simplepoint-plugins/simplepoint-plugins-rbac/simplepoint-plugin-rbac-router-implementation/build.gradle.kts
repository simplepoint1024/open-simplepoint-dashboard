dependencies {
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-implementation"))
    testImplementation(project(":simplepoint-service-router:simplepoint-service-router-core"))
    testImplementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    testImplementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-repository"))
    testRuntimeOnly(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-module"))
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
    implementation(project(":simplepoint-plugin:simplepoint-plugin-api"))
    implementation(project(":simplepoint-remoting:simplepoint-remoting-core"))
    implementation(project(":simplepoint-security:simplepoint-security-core"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-router-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))
    implementation("org.springframework:spring-tx")
}

tasks.test {
    val postgres = providers.environmentVariable("SP_RESOURCE_TEST_POSTGRES_URL").isPresent
    inputs.property("resourceRegistrationPostgres", postgres)
    outputs.upToDateWhen { !postgres }
    outputs.doNotCacheIf("Uses a disposable PostgreSQL resource catalog") { postgres }
}

// Exercise the authoritative platform snapshots against real catalog transactions, without
// importing HTTP implementations into this domain's runtime or duplicating their declarations.
val navigationDeclarations = mapOf(
    "common-ui" to "simplepoint-plugins/simplepoint-plugin-common-ui",
    "app-http" to "simplepoint-plugins/simplepoint-plugin-app/simplepoint-plugin-app-http",
    "workflow-http" to "simplepoint-plugins/simplepoint-plugin-workflow/simplepoint-plugin-workflow-http",
    "dna-http" to "simplepoint-plugins/simplepoint-plugin-dna/simplepoint-plugin-dna-http",
    "ai-http" to "simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-http",
    "auditing-http" to "simplepoint-plugins/simplepoint-plugins-auditing/simplepoint-plugin-auditing-http"
)
tasks.test {
    navigationDeclarations.forEach { (module, projectPath) ->
        val declaration = rootProject.file("$projectPath/src/main/resources/META-INF/simplepoint/modules/$module/resources.json")
        inputs.file(declaration)
        systemProperty("navigation.declaration.$module", declaration.absolutePath)
    }
}
