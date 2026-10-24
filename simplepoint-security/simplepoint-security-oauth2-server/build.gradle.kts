dependencies {
    // Optional modular browser boundary; legacy authorization does not activate resource auto-config.
    compileOnly(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    testImplementation(project(":simplepoint-security:simplepoint-security-oauth2-resource"))
    api(project(":simplepoint-security:simplepoint-security-authorization-audit"))
    implementation(project(":simplepoint-platform:simplepoint-platform-http-servlet"))
    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-module"))
    api(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-module"))
    implementation(project(":simplepoint-events:simplepoint-events-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-auditing:simplepoint-plugin-auditing-logging-api"))

    api(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-authentication"))
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    api(libs.oauth2.oidc.sdk)
    api(libs.spring.authorization.server)
    api(project(":simplepoint-security:simplepoint-security-core"))
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation(project(":simplepoint-cache:simplepoint-cache-core"))
    implementation(project(":simplepoint-data:simplepoint-data-jpa"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-rbac:simplepoint-plugin-rbac-core-implementation"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-api"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-repository"))
    implementation(project(":simplepoint-plugins:simplepoint-plugins-oidc:simplepoint-plugin-oidc-service"))
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.2")
    implementation("org.apache.tomcat.embed:tomcat-embed-core")
    implementation("commons-codec:commons-codec:1.16.0")
}

// Generated from this artifact's actual static resources; no parallel list of public asset paths.
val authorizationAssets = layout.buildDirectory.dir("generated/authorization-http-assets")
val generateAuthorizationHttpAssets by tasks.registering {
    val resources = layout.projectDirectory.dir("src/main/resources/static")
    inputs.dir(resources)
    outputs.dir(authorizationAssets)
    doLast {
        val index = authorizationAssets.get().file(
            "META-INF/simplepoint/modules/authorization-http/http-assets.imports").asFile
        index.parentFile.mkdirs()
        index.writeText(resources.asFile.walkTopDown().filter { it.isFile }
            .map { "/" + it.relativeTo(resources.asFile).invariantSeparatorsPath }
            .sorted().joinToString("\n", postfix = "\n"))
    }
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(generateAuthorizationHttpAssets)
    from(authorizationAssets)
}
