package org.simplepoint.plugin.ai.mcp.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.mcp.api.constants.AiMcpPaths;
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpInvocation;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpInvocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only management endpoint for metadata-only MCP invocation audit. */
@RestController
@RequestMapping(AiMcpPaths.INVOCATIONS)
@Tag(name = "AI MCP Invocation", description = "MCP 调用审计元数据")
public class AiMcpInvocationController {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiMcpInvocationController.class
  );

  private static final String REQUEST_INVALID =
      "AI_MCP_INVOCATION_REQUEST_INVALID";

  private final AiMcpInvocationService service;

  /** Creates the invocation audit controller. */
  public AiMcpInvocationController(final AiMcpInvocationService service) {
    this.service = service;
  }

  /** Pages current-scope invocation metadata without payloads or credentials. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-servers.view')"
  )
  @Operation(summary = "分页查询 MCP 调用审计元数据")
  public Response<Page<AiMcpInvocation>> findAll(
      @RequestParam(value = "serverId", required = false) final String serverId,
      final Pageable pageable
  ) {
    try {
      return Response.limit(
          service.findAll(serverId, pageable), AiMcpInvocation.class
      );
    } catch (IllegalArgumentException ex) {
      LOG.warn("MCP invocation audit request rejected with {}",
          REQUEST_INVALID, ex);
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, REQUEST_INVALID, ex
      );
    }
  }
}
