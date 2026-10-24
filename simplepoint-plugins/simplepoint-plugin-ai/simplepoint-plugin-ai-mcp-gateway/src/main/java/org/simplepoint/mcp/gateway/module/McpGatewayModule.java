package org.simplepoint.mcp.gateway.module;

import org.simplepoint.platform.composition.ModuleHttp;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Configuration;

/** Optional protocol gateway, reusable without loading AI persistence or a runnable host. */
@PlatformModule(
    id = "mcp-gateway",
    packages = {
      "org.simplepoint.mcp.gateway.module",
      "org.simplepoint.mcp.gateway.client",
      "org.simplepoint.mcp.gateway.config",
      "org.simplepoint.mcp.gateway.event",
      "org.simplepoint.mcp.gateway.publication",
      "org.simplepoint.mcp.gateway.rest",
      "org.simplepoint.mcp.gateway.security"
    })
@ModuleHttp(namespace = "/", scopedAuthorization = false, routes = McpGatewayHttpRoutes.class)
@ModuleMigrations(ownsSchema = false)
@Configuration(proxyBeanMethods = false)
public class McpGatewayModule {}
