package org.simplepoint.plugin.ai.mcp.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workbench status endpoint for the independent MCP Gateway.
 */
@RestController
@RequestMapping(AiMcpPaths.GATEWAY)
@Tag(name = "AI MCP Gateway", description = "MCP Gateway 状态和协议基线")
public class AiMcpGatewayController {

  private final McpGatewayOperations gatewayOperations;

  /**
   * Creates the Gateway status controller.
   *
   * @param gatewayOperations Gateway control client
   */
  public AiMcpGatewayController(final McpGatewayOperations gatewayOperations) {
    this.gatewayOperations = gatewayOperations;
  }

  /**
   * Returns Gateway status without exposing its private control API.
   *
   * @return Gateway status
   */
  @GetMapping("/status")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.mcp-servers.view') "
          + "or hasAuthority('ai.workbench.mcp-gateway.view')"
  )
  @Operation(summary = "查询 MCP Gateway 状态")
  public Response<?> status() {
    return Response.okay(gatewayOperations.status());
  }
}
