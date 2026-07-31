package org.simplepoint.mcp.gateway.rest;

import org.simplepoint.mcp.gateway.security.SkillCapabilityTokenVerifier;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayCancellationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayConnection;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthDiscoveryResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthRegistrationResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOauthTokenResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayOperations;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayStatus;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayWorkflowToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpSkillCapabilityClaims;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private control-plane API for remote MCP operations.
 */
@RestController
@PreAuthorize("hasRole('MCP_GATEWAY_SERVICE')")
public class McpGatewayInternalController {

  private final McpGatewayOperations operations;

  private final SkillCapabilityTokenVerifier capabilityTokenVerifier;

  /**
   * Creates the internal controller.
   *
   * @param operations remote MCP operations
   */
  public McpGatewayInternalController(
      final McpGatewayOperations operations,
      final SkillCapabilityTokenVerifier capabilityTokenVerifier
  ) {
    this.operations = operations;
    this.capabilityTokenVerifier = capabilityTokenVerifier;
  }

  /**
   * Returns this Gateway instance status.
   *
   * @return status and protocol baseline
   */
  @GetMapping(AiMcpPaths.INTERNAL_STATUS)
  public McpGatewayStatus status() {
    return operations.status();
  }

  /**
   * Initializes and discovers an MCP server.
   *
   * @param connection connection material
   * @return discovery result
   */
  @PostMapping(AiMcpPaths.INTERNAL_DISCOVER)
  public McpGatewayDiscoveryResult discover(
      @RequestBody final McpGatewayConnection connection
  ) {
    return operations.discover(connection);
  }

  /**
   * Invokes one remote MCP tool.
   *
   * @param request tool call request
   * @return MCP tool result
   */
  @PostMapping(AiMcpPaths.INTERNAL_CALL_TOOL)
  @ResponseStatus(HttpStatus.OK)
  public McpGatewayToolCallResult callTool(
      @RequestBody final McpGatewayToolCallRequest request
  ) {
    return operations.callTool(request);
  }

  /**
   * Cooperatively cancels one trusted cluster-wide operation.
   */
  @PostMapping(AiMcpPaths.INTERNAL_CANCEL_OPERATION)
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void cancelOperation(
      @RequestBody final McpGatewayCancellationRequest request
  ) {
    operations.cancel(request);
  }

  /**
   * Invokes one exact Skill Workflow Tool with a single-use capability.
   */
  @PostMapping(AiMcpPaths.INTERNAL_CALL_WORKFLOW_TOOL)
  @ResponseStatus(HttpStatus.OK)
  public McpGatewayToolCallResult callWorkflowTool(
      @RequestBody final McpGatewayWorkflowToolCallRequest request
  ) {
    McpSkillCapabilityClaims claims =
        capabilityTokenVerifier.verifyAndConsume(request);
    McpGatewayToolCallResult result = operations.callTool(request.call());
    capabilityTokenVerifier.validateResult(claims, result);
    return result;
  }

  /**
   * Renders one exact Skill Workflow Prompt with a single-use capability.
   */
  @PostMapping(AiMcpPaths.INTERNAL_GET_WORKFLOW_PROMPT)
  @ResponseStatus(HttpStatus.OK)
  public McpGatewayPromptGetResult getWorkflowPrompt(
      @RequestBody final McpGatewayWorkflowPromptGetRequest request
  ) {
    McpSkillCapabilityClaims claims =
        capabilityTokenVerifier.verifyAndConsume(request);
    McpGatewayPromptGetResult result = operations.getPrompt(request.call());
    capabilityTokenVerifier.validateResult(claims, result);
    return result;
  }

  /**
   * Reads one exact Skill Workflow Resource with a single-use capability.
   */
  @PostMapping(AiMcpPaths.INTERNAL_READ_WORKFLOW_RESOURCE)
  @ResponseStatus(HttpStatus.OK)
  public McpGatewayResourceReadResult readWorkflowResource(
      @RequestBody final McpGatewayWorkflowResourceReadRequest request
  ) {
    McpSkillCapabilityClaims claims =
        capabilityTokenVerifier.verifyAndConsume(request);
    McpGatewayResourceReadResult result =
        operations.readResource(request.call());
    capabilityTokenVerifier.validateResult(claims, result);
    return result;
  }

  /**
   * Reads one remote MCP resource.
   */
  @PostMapping(AiMcpPaths.INTERNAL_READ_RESOURCE)
  public McpGatewayResourceReadResult readResource(
      @RequestBody final McpGatewayResourceReadRequest request
  ) {
    return operations.readResource(request);
  }

  /**
   * Renders one remote MCP prompt.
   */
  @PostMapping(AiMcpPaths.INTERNAL_GET_PROMPT)
  public McpGatewayPromptGetResult getPrompt(
      @RequestBody final McpGatewayPromptGetRequest request
  ) {
    return operations.getPrompt(request);
  }

  /**
   * Discovers OAuth metadata for a protected remote MCP resource.
   */
  @PostMapping(AiMcpPaths.INTERNAL_OAUTH_DISCOVER)
  public McpGatewayOauthDiscoveryResult discoverOauth(
      @RequestBody final McpGatewayOauthDiscoveryRequest request
  ) {
    return operations.discoverOauth(request);
  }

  /**
   * Dynamically registers an OAuth client.
   */
  @PostMapping(AiMcpPaths.INTERNAL_OAUTH_REGISTER)
  public McpGatewayOauthRegistrationResult registerOauthClient(
      @RequestBody final McpGatewayOauthRegistrationRequest request
  ) {
    return operations.registerOauthClient(request);
  }

  /**
   * Exchanges an OAuth authorization code or refresh token.
   */
  @PostMapping(AiMcpPaths.INTERNAL_OAUTH_TOKEN)
  public McpGatewayOauthTokenResult exchangeOauthToken(
      @RequestBody final McpGatewayOauthTokenRequest request
  ) {
    return operations.exchangeOauthToken(request);
  }
}
