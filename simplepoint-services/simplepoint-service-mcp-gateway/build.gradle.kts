plugins {
    application
}

application {
    mainClass.set("org.simplepoint.mcp.gateway.McpGatewayApplication")
}

configurations.configureEach {
    // Parent service conventions add database drivers to every service. The
    // protocol Gateway intentionally has no database connectivity.
    exclude(group = "org.postgresql", module = "postgresql")
    exclude(group = "mysql", module = "mysql-connector-java")
    exclude(group = "com.mysql", module = "mysql-connector-j")
}

dependencies {
    implementation(platform(libs.mcp.bom))
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2)

    implementation(project(":simplepoint-boot:simplepoint-boot-starter"))
    implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-api")) {
        // The Gateway consumes only protocol DTOs. Persistence and AI control-plane
        // dependencies remain inside simplepoint-service-ai.
        isTransitive = false
    }

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
