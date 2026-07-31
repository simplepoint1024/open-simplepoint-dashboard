package org.simplepoint.plugin.ai.mcp.rest.controller;

import java.util.Map;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayPromptGetResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayResourceReadResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayToolCallResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpGatewayUpstreamEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationChangeEvent;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationManifest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationPromptGetRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationResourceReadRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskCreateRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskListRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationTaskRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpPublicationToolCallRequest;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskDescriptor;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskListResult;
import org.simplepoint.plugin.ai.mcp.api.gateway.McpTaskResult;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private runtime API consumed by independently scaled Gateway replicas.
 */
@RestController
@RequestMapping(AiMcpPaths.INTERNAL_PUBLICATIONS)
@PreAuthorize("hasRole('MCP_GATEWAY_SERVICE')")
public class AiMcpPublicationInternalController {

  private final AiMcpPublicationRuntimeService runtimeService;

  private final AiMcpTaskService taskService;

  /**
   * Creates the trusted runtime controller.
   */
  public AiMcpPublicationInternalController(
      final AiMcpPublicationRuntimeService runtimeService,
      final AiMcpTaskService taskService
  ) {
    this.runtimeService = runtimeService;
    this.taskService = taskService;
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
   * Creates one durable task-augmented Tool call.
   */
  @PostMapping("/tasks/create")
  public McpTaskDescriptor createTask(
      @RequestBody final McpPublicationTaskCreateRequest request
  ) {
    return taskService.create(request);
  }

  /**
   * Returns one authorization-bound Task.
   */
  @PostMapping("/tasks/get")
  public McpTaskDescriptor getTask(
      @RequestBody final McpPublicationTaskRequest request
  ) {
    return taskService.find(request);
  }

  /**
   * Lists authorization-bound Tasks with an opaque cursor.
   */
  @PostMapping("/tasks/list")
  public McpTaskListResult listTasks(
      @RequestBody final McpPublicationTaskListRequest request
  ) {
    return taskService.findAll(request);
  }

  /**
   * Returns the exact underlying Tool result when available.
   */
  @PostMapping("/tasks/result")
  public McpTaskResult taskResult(
      @RequestBody final McpPublicationTaskRequest request
  ) {
    return taskService.result(request);
  }

  /**
   * Cooperatively cancels a non-terminal Task.
   */
  @PostMapping("/tasks/cancel")
  public McpTaskDescriptor cancelTask(
      @RequestBody final McpPublicationTaskRequest request
  ) {
    return taskService.cancel(request);
  }

  /**
   * Preserves invalid Task lifecycle operations as client errors at the
   * trusted control-plane boundary.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> invalidTaskRequest(
      final IllegalArgumentException exception
  ) {
    return Map.of("message", exception.getMessage());
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
