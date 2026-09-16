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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

  private static final Logger LOG = LoggerFactory.getLogger(
      AiMcpServerDefinitionController.class
  );

  private static final String REQUEST_INVALID =
      "AI_MCP_SERVER_REQUEST_INVALID";

  private static final String OPERATION_CONFLICT =
      "AI_MCP_SERVER_OPERATION_CONFLICT";

  private static final String CAPABILITY_READ_AUTHORIZATION =
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.mcp-servers.view') "
          + "or hasAuthority('ai.workbench.tools.view') "
          + "or hasAuthority('ai.workbench.tools.call') "
          + "or hasAuthority('ai.workbench.mcp-servers.read-resource') "
          + "or hasAuthority('ai.workbench.mcp-servers.get-prompt')";

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
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.mcp-servers.view') "
          + "or hasAuthority('ai.workbench.tools.view') "
          + "or hasAuthority('ai.workbench.tools.call') "
          + "or hasAuthority('ai.workbench.mcp-servers.read-resource') "
          + "or hasAuthority('ai.workbench.mcp-servers.get-prompt') "
          + "or hasAuthority('ai.workbench.mcp-publications.view') "
          + "or hasAuthority('ai.workbench.mcp-publications.create') "
          + "or hasAuthority('ai.workbench.mcp-publications.edit') "
          + "or hasAuthority('ai.workbench.mcp-publications.delete') "
          + "or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')"
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
  @PreAuthorize(CAPABILITY_READ_AUTHORIZATION)
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
  @PreAuthorize(CAPABILITY_READ_AUTHORIZATION)
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

  /** Returns the current user's OAuth connection status for this provider. */
  @GetMapping("/{id}/oauth/connection")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.authorize')"
  )
  public Response<?> oauthConnection(@PathVariable("id") final String id) {
    return invoke(() -> service.currentProviderConnection(id).orElse(null));
  }

  /** Removes the current user's OAuth token material. */
  @DeleteMapping("/{id}/oauth/connection")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.authorize')"
  )
  public Response<?> disconnectOauth(@PathVariable("id") final String id) {
    return invoke(() -> service.disconnectProvider(id));
  }

  /**
   * Returns operation permissions used by the consolidated MCP capability view.
   */
  @GetMapping("/workbench-permissions")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.mcp-servers.view') "
          + "or hasAuthority('ai.workbench.mcp-gateway.view') "
          + "or hasAuthority('ai.workbench.tools.view') "
          + "or hasAuthority('ai.workbench.tools.call') "
          + "or hasAuthority('ai.workbench.mcp-servers.read-resource') "
          + "or hasAuthority('ai.workbench.mcp-servers.get-prompt') "
          + "or hasAuthority('ai.workbench.mcp-publications.view') "
          + "or hasAuthority('ai.workbench.mcp-publications.create') "
          + "or hasAuthority('ai.workbench.mcp-publications.edit') "
          + "or hasAuthority('ai.workbench.mcp-publications.delete') "
          + "or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage') "
          + "or hasAuthority('ai.workbench.runtime.secrets.manage')"
  )
  @Operation(summary = "查询 MCP 工作台功能权限")
  public Response<?> workbenchPermissions(final Authentication authentication) {
    return ok(Map.ofEntries(
        Map.entry("callTool", hasAuthority(
            authentication,
            "ai.workbench.tools.call"
        )),
        Map.entry("managePools", hasAuthority(
            authentication,
            "ai.workbench.runtime.pools.manage"
        )),
        Map.entry("manageSecrets", hasAuthority(
            authentication,
            "ai.workbench.runtime.secrets.manage"
        )),
        Map.entry("manageWorkloads", hasAuthority(
            authentication,
            "ai.workbench.runtime.workloads.manage"
        )),
        Map.entry("viewCapabilities", hasAnyAuthority(
            authentication,
            "ai.workbench.mcp-servers.view",
            "ai.workbench.tools.view",
            "ai.workbench.tools.call",
            "ai.workbench.mcp-servers.read-resource",
            "ai.workbench.mcp-servers.get-prompt"
        )),
        Map.entry("viewGateway", hasAnyAuthority(
            authentication,
            "ai.workbench.mcp-servers.view",
            "ai.workbench.mcp-gateway.view"
        )),
        Map.entry("viewNodes", hasAuthority(
            authentication,
            "ROLE_Administrator"
        )),
        Map.entry("viewPublications", hasAnyAuthority(
            authentication,
            "ai.workbench.mcp-publications.view",
            "ai.workbench.mcp-publications.create",
            "ai.workbench.mcp-publications.edit",
            "ai.workbench.mcp-publications.delete"
        )),
        Map.entry("viewRuntime", hasAnyAuthority(
            authentication,
            "ai.workbench.runtime.view",
            "ai.workbench.runtime.pools.manage",
            "ai.workbench.runtime.workloads.manage",
            "ai.workbench.runtime.secrets.manage"
        )),
        Map.entry("viewServers", hasAuthority(
            authentication,
            "ai.workbench.mcp-servers.view"
        )),
        Map.entry("readResource", hasAuthority(
            authentication,
            "ai.workbench.mcp-servers.read-resource"
        )),
        Map.entry("getPrompt", hasAuthority(
            authentication,
            "ai.workbench.mcp-servers.get-prompt"
        ))
    ));
  }

  /**
   * Lists tools from the active immutable capability snapshot.
   */
  @GetMapping("/{id}/tools")
  @PreAuthorize(CAPABILITY_READ_AUTHORIZATION)
  @Operation(summary = "查询 MCP Tool 快照")
  public Response<?> tools(@PathVariable("id") final String id) {
    return invoke(() -> service.listTools(id));
  }

  /**
   * Lists resources from the active immutable capability snapshot.
   */
  @GetMapping("/{id}/resources")
  @PreAuthorize(CAPABILITY_READ_AUTHORIZATION)
  @Operation(summary = "查询 MCP Resource 快照")
  public Response<?> resources(@PathVariable("id") final String id) {
    return invoke(() -> service.listResources(id));
  }

  /**
   * Lists resource templates from the active immutable capability snapshot.
   */
  @GetMapping("/{id}/resource-templates")
  @PreAuthorize(CAPABILITY_READ_AUTHORIZATION)
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
  @PreAuthorize(CAPABILITY_READ_AUTHORIZATION)
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
    } catch (IllegalArgumentException ex) {
      return rejected(ex, REQUEST_INVALID, false);
    } catch (IllegalStateException ex) {
      return rejected(ex, OPERATION_CONFLICT, true);
    }
  }

  private Response<?> rejected(
      final RuntimeException exception,
      final String errorCode,
      final boolean conflict
  ) {
    LOG.warn("MCP Server API request rejected with {}", errorCode, exception);
    ResponseEntity.BodyBuilder response = conflict
        ? ResponseEntity.status(409) : ResponseEntity.badRequest();
    return Response.of(response
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("errorCode", errorCode)));
  }

  private boolean hasAuthority(
      final Authentication authentication,
      final String authority
  ) {
    return authentication != null && authentication.getAuthorities().stream()
        .anyMatch(granted -> "ROLE_Administrator".equals(granted.getAuthority())
            || authority.equals(granted.getAuthority()));
  }

  private boolean hasAnyAuthority(
      final Authentication authentication,
      final String... authorities
  ) {
    if (hasAuthority(authentication, "ROLE_Administrator")) {
      return true;
    }
    return authentication != null && authentication.getAuthorities().stream()
        .map(granted -> granted.getAuthority())
        .anyMatch(granted -> Set.of(authorities).contains(granted));
  }
}
