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
import org.simplepoint.plugin.ai.mcp.api.entity.AiMcpPublication;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationRuntimeService;
import org.simplepoint.plugin.ai.mcp.api.service.AiMcpPublicationService;
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
 * AI workbench management endpoints for northbound MCP publications.
 */
@RestController
@RequestMapping(AiMcpPaths.PUBLICATIONS)
@Tag(name = "AI MCP Publication", description = "对外开放标准 MCP Server")
public class AiMcpPublicationController
    extends BaseController<AiMcpPublicationService, AiMcpPublication, String> {

  private final AiMcpPublicationRuntimeService runtimeService;

  /**
   * Creates the publication management controller.
   */
  public AiMcpPublicationController(
      final AiMcpPublicationService service,
      final AiMcpPublicationRuntimeService runtimeService
  ) {
    super(service);
    this.runtimeService = runtimeService;
  }

  /**
   * Pages publications in the current management scope.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-publications.view')"
  )
  @Operation(summary = "分页查询 MCP Publication")
  public Response<Page<AiMcpPublication>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), AiMcpPublication.class);
  }

  /**
   * Returns one publication.
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-publications.view')"
  )
  @Operation(summary = "查询 MCP Publication")
  public Response<?> find(@PathVariable("id") final String id) {
    return invoke(() -> service.findActiveById(id)
        .orElseThrow(() -> new IllegalArgumentException(
            "MCP publication does not exist"
        )));
  }

  /**
   * Returns the immutable manifest currently served by the MCP Gateway.
   */
  @GetMapping("/{id}/manifest")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-publications.view')"
  )
  @Operation(summary = "查询 MCP Publication 最终发布清单")
  public Response<?> manifest(@PathVariable("id") final String id) {
    return invoke(() -> {
      AiMcpPublication publication = service.findActiveById(id)
          .orElseThrow(() -> new IllegalArgumentException(
              "MCP publication does not exist"
          ));
      return runtimeService.manifest(publication.getCode());
    });
  }

  /**
   * Creates one publication.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-publications.create')"
  )
  @Operation(summary = "新增 MCP Publication")
  public Response<?> add(@RequestBody final AiMcpPublication data) {
    return invoke(() -> service.create(data));
  }

  /**
   * Updates one publication.
   */
  @PutMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-publications.edit')"
  )
  @Operation(summary = "修改 MCP Publication")
  public Response<?> modify(@RequestBody final AiMcpPublication data) {
    return invoke(() -> service.modifyById(data));
  }

  /**
   * Soft deletes selected publications.
   */
  @DeleteMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.mcp-publications.delete')"
  )
  @Operation(summary = "删除 MCP Publication")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    return invoke(() -> {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return idSet;
    });
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
