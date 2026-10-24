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
    implementation(project(":simplepoint-plugins:simplepoint-plugin-ai:simplepoint-plugin-ai-mcp-gateway"))
    implementation(project(":simplepoint-boot:simplepoint-boot-starter"))
    implementation(project(":simplepoint-boot:simplepoint-boot-config-consul-starter"))
    implementation("org.springframework.security:spring-security-config")
}
