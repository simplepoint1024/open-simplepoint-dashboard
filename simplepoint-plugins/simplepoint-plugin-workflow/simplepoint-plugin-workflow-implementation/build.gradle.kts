dependencies {
    api(project(":simplepoint-plugins:simplepoint-plugin-workflow:simplepoint-plugin-workflow-api"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-app:simplepoint-plugin-app-form-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-tenant-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-notification:simplepoint-plugin-notification-api"))
    implementation(platform("org.flowable:flowable-bom:${rootProject.libs.versions.flowable.get()}"))
    implementation("org.flowable:flowable-spring-boot-starter-process")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.postgresql:postgresql")
}

tasks.withType<Test>().configureEach {
    // Switching the real-dialect gate must not reuse an H2 result or a skipped PostgreSQL plan test.
    val postgres = providers.environmentVariable("WORKFLOW_TEST_POSTGRES")
        .map { if (it == "1") "1" else "0" }.orElse("0")
    inputs.property("workflowTestPostgres", postgres)
    environment("WORKFLOW_TEST_POSTGRES", postgres.get())
    outputs.upToDateWhen { postgres.get() != "1" }
    outputs.doNotCacheIf("The opt-in database gate must execute against its disposable PostgreSQL") {
        postgres.get() == "1"
    }
}

tasks.named<ProcessResources>("processTestResources") {
    from(rootProject.file("simplepoint-react/libs/components/ExpressionEditor/stringLiterals.fixture.json")) {
        into("workflow-expressions")
    }
    from(rootProject.file("simplepoint-react/libs/components/ExpressionEditor/numericLiterals.fixture.json")) {
        into("workflow-expressions")
    }
    from(rootProject.file("simplepoint-react/libs/components/ExpressionEditor/priorityLiterals.fixture.json")) {
        into("workflow-expressions")
    }
    from(rootProject.file("simplepoint-react/libs/components/WorkflowDesigner/dueDateLiterals.fixture.json")) {
        into("workflow-expressions")
    }
    from(rootProject.file("simplepoint-react/libs/components/TenantDirectorySelect/roleAuthorities.fixture.json")) {
        into("workflow-expressions")
    }
}
