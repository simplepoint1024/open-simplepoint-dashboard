package org.simplepoint.plugin.ai.runtime.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.runtime.api.constants.AiRuntimePaths;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeNodeService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-only read API for OCI runtime node operations.
 */
@RestController
@RequestMapping(AiRuntimePaths.NODES)
@PreAuthorize("hasRole('Administrator')")
@Tag(name = "AI Runtime Node", description = "平台 OCI 工具运行节点")
public class AiRuntimeNodeController {

  private final AiRuntimeNodeService service;

  /**
   * Creates the platform runtime-node controller.
   */
  public AiRuntimeNodeController(final AiRuntimeNodeService service) {
    this.service = service;
  }

  /**
   * Pages all runtime nodes.
   */
  @GetMapping
  @Operation(summary = "分页查询 OCI Runtime 节点")
  public Response<Page<AiRuntimeNode>> findAll(final Pageable pageable) {
    return Response.limit(service.findAll(pageable), AiRuntimeNode.class);
  }

  /**
   * Returns one runtime node.
   */
  @GetMapping("/{nodeId}")
  @Operation(summary = "查询 OCI Runtime 节点")
  public Response<AiRuntimeNode> find(
      @PathVariable("nodeId") final String nodeId
  ) {
    return service.findActiveByNodeId(nodeId)
        .map(Response::okay)
        .orElseGet(Response::nf);
  }
}
