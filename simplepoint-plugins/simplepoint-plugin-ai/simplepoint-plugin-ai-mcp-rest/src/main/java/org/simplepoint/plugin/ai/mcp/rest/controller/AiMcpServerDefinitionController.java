package org.simplepoint.plugin.ai.mcp.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpServerDefinition;
import org.simplepoint.plugin.ai.mcp.api.model.McpOauthCallbackCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpPromptGetCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpResourceReadCommand;
import org.simplepoint.plugin.ai.mcp.api.model.McpToolCallCommand;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpServerDefinitionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI workbench endpoints for MCP Server registration and discovery.
 */
@RestController
@RequestMapping(AiMcpPaths.SERVERS)
@Tag(name = "AI MCP Server", description = "注册、发现和测试远程 MCP Server")
public class AiMcpServerDefinitionController
    extends BaseController<AiMcpServerDefinitionService, AiMcpServerDefinition, String> {

  /**
   * Creates the controller.
   *
   * @param service MCP server service
   */
  public AiMcpServerDefinitionController(final AiMcpServerDefinitionService service) {
    super(service);
  }

  /**
   * Pages MCP server registrations in the active platform or tenant scope.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "分页查询 MCP Server")
  public Response<Page<AiMcpServerDefinition>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), AiMcpServerDefinition.class);
  }

  /**
   * Returns one MCP server registration.
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "查询 MCP Server")
  public Response<?> find(@PathVariable("id") final String id) {
    return invoke(() -> service.findActiveById(id)
        .orElseThrow(() -> new IllegalArgumentException("MCP server does not exist")));
  }

  /**
   * Creates an MCP server registration.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.create')"
  )
  @Operation(summary = "新增 MCP Server")
  public Response<?> add(@RequestBody final AiMcpServerDefinition data) {
    return invoke(() -> service.create(data));
  }

  /**
   * Updates an MCP server registration.
   */
  @PutMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.edit')"
  )
  @Operation(summary = "修改 MCP Server")
  public Response<?> modify(@RequestBody final AiMcpServerDefinition data) {
    return invoke(() -> service.modifyById(data));
  }

  /**
   * Deletes MCP server registrations.
   */
  @DeleteMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.delete')"
  )
  @Operation(summary = "删除 MCP Server")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    return invoke(() -> {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return idSet;
    });
  }

  /**
   * Initializes the remote server and persists a new capability snapshot.
   */
  @PostMapping("/{id}/discover")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.discover')"
  )
  @Operation(summary = "发现 MCP Server 能力")
  public Response<?> discover(@PathVariable("id") final String id) {
    return invoke(() -> service.discover(id));
  }

  /**
   * Pages immutable capability discovery snapshots for one MCP Server.
   */
  @GetMapping("/{id}/snapshots")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "分页查询 MCP 能力快照历史")
  public Response<?> snapshots(
      @PathVariable("id") final String id,
      final Pageable pageable
  ) {
    return invoke(() -> service.listSnapshots(id, pageable));
  }

  /**
   * Returns one decoded immutable capability snapshot.
   */
  @GetMapping("/{id}/snapshots/{snapshotId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "查询 MCP 能力快照详情")
  public Response<?> snapshot(
      @PathVariable("id") final String id,
      @PathVariable("snapshotId") final String snapshotId
  ) {
    return invoke(() -> service.getSnapshot(id, snapshotId));
  }

  /**
   * Starts OAuth Authorization Code + PKCE for a remote MCP connection.
   */
  @PostMapping("/{id}/oauth/authorize")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.authorize')"
  )
  @Operation(summary = "发起 MCP OAuth 2.1 授权")
  public Response<?> authorize(@PathVariable("id") final String id) {
    return invoke(() -> service.startOauthAuthorization(id));
  }

  /**
   * Completes an OAuth redirect relayed by the authenticated workbench page.
   */
  @PostMapping("/oauth/callback")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.authorize')"
  )
  @Operation(summary = "完成 MCP OAuth 2.1 授权")
  public Response<?> oauthCallback(
      @RequestBody final McpOauthCallbackCommand command
  ) {
    return invoke(() -> service.completeOauthAuthorization(command));
  }

  /**
   * Lists tools from the active immutable capability snapshot.
   */
  @GetMapping("/{id}/tools")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.tools.view')"
  )
  @Operation(summary = "查询 MCP Tool 快照")
  public Response<?> tools(@PathVariable("id") final String id) {
    return invoke(() -> service.listTools(id));
  }

  /**
   * Lists resources from the active immutable capability snapshot.
   */
  @GetMapping("/{id}/resources")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "查询 MCP Resource 快照")
  public Response<?> resources(@PathVariable("id") final String id) {
    return invoke(() -> service.listResources(id));
  }

  /**
   * Lists resource templates from the active immutable capability snapshot.
   */
  @GetMapping("/{id}/resource-templates")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "查询 MCP Resource Template 快照")
  public Response<?> resourceTemplates(@PathVariable("id") final String id) {
    return invoke(() -> service.listResourceTemplates(id));
  }

  /**
   * Reads a resource through the independent Gateway.
   */
  @PostMapping("/{id}/resources/read")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.mcp-servers.read-resource')"
  )
  @Operation(summary = "读取 MCP Resource")
  public Response<?> readResource(
      @PathVariable("id") final String id,
      @RequestBody final McpResourceReadCommand command
  ) {
    return invoke(() -> service.readResource(id, command));
  }

  /**
   * Lists prompts from the active immutable capability snapshot.
   */
  @GetMapping("/{id}/prompts")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "查询 MCP Prompt 快照")
  public Response<?> prompts(@PathVariable("id") final String id) {
    return invoke(() -> service.listPrompts(id));
  }

  /**
   * Renders a prompt through the independent Gateway.
   */
  @PostMapping("/{id}/prompts/get")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.mcp-servers.get-prompt')"
  )
  @Operation(summary = "获取 MCP Prompt")
  public Response<?> getPrompt(
      @PathVariable("id") final String id,
      @RequestBody final McpPromptGetCommand command
  ) {
    return invoke(() -> service.getPrompt(id, command));
  }

  /**
   * Calls a tool for workbench testing through the independent Gateway.
   */
  @PostMapping("/{id}/tools/call")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.tools.call')"
  )
  @Operation(summary = "调用 MCP Tool")
  public Response<?> callTool(
      @PathVariable("id") final String id,
      @RequestBody final McpToolCallCommand command
  ) {
    return invoke(() -> service.callTool(id, command));
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return ok(operation.get());
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return Response.of(
          ResponseEntity.badRequest()
              .contentType(MediaType.TEXT_PLAIN)
              .body(ex.getMessage())
      );
    }
  }
}
