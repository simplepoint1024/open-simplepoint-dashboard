package org.simplepoint.mcp.gateway;

import org.simplepoint.boot.starter.Boot;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Independent MCP Gateway process.
 */
@Boot
@EnableMethodSecurity
public class McpGatewayApplication {

  /**
   * Starts the MCP Gateway.
   *
   * @param args command-line arguments
   */
  public static void main(final String[] args) {
    org.simplepoint.boot.starter.Application.run(McpGatewayApplication.class, args);
  }
}
