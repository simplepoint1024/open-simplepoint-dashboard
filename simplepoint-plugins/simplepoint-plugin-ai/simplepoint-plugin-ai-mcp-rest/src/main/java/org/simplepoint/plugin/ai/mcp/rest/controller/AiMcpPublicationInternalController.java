package org.simplepoint.plugin.ai.mcp.rest.controller;

import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationChangeEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private runtime API consumed by independently scaled Gateway replicas.
 */
@RestController
@RequestMapping(AiMcpPaths.INTERNAL_PUBLICATIONS)
@PreAuthorize("hasRole('MCP_GATEWAY_SERVICE')")
public class AiMcpPublicationInternalController {

  private final AiMcpPublicationRuntimeService runtimeService;

  /**
   * Creates the trusted runtime controller.
   */
  public AiMcpPublicationInternalController(
      final AiMcpPublicationRuntimeService runtimeService
  ) {
    this.runtimeService = runtimeService;
  }

  /**
   * Returns one publication manifest.
   */
  @GetMapping("/{code}/manifest")
  public McpPublicationManifest manifest(@PathVariable("code") final String code) {
    return runtimeService.manifest(code);
  }

  /**
   * Calls one published tool.
   */
  @PostMapping("/tools/call")
  public McpGatewayToolCallResult callTool(
      @RequestBody final McpPublicationToolCallRequest request
  ) {
    return runtimeService.callPublishedTool(request);
  }

  /**
   * Reads one published resource.
   */
  @PostMapping("/resources/read")
  public McpGatewayResourceReadResult readResource(
      @RequestBody final McpPublicationResourceReadRequest request
  ) {
    return runtimeService.readPublishedResource(request);
  }

  /**
   * Renders one published prompt.
   */
  @PostMapping("/prompts/get")
  public McpGatewayPromptGetResult getPrompt(
      @RequestBody final McpPublicationPromptGetRequest request
  ) {
    return runtimeService.getPublishedPrompt(request);
  }

  /**
   * Persists one coalesced upstream notification and resolves publications.
   */
  @PostMapping("/upstream-events")
  public McpPublicationChangeEvent upstreamEvent(
      @RequestBody final McpGatewayUpstreamEvent event
  ) {
    return runtimeService.handleUpstreamEvent(event);
  }
}
