package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.service.FederationSqlConsoleService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationQueryModels;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Federation SQL console endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.SQL_CONSOLE, DnaFederationPaths.PLATFORM_SQL_CONSOLE})
@Tag(name = "联邦 SQL 控制台", description = "用于执行和分析联邦只读 SQL")
public class FederationSqlConsoleController {

  private final FederationSqlConsoleService service;

  /**
   * Creates a federation SQL console controller.
   *
   * @param service SQL console service
   */
  public FederationSqlConsoleController(final FederationSqlConsoleService service) {
    this.service = service;
  }

  /**
   * Explains a federation SQL query.
   *
   * @param request SQL console request
   * @return explain response
   */
  @PostMapping("/explain")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.sql-console.explain')")
  @Operation(summary = "查看执行计划", description = "生成联邦只读 SQL 的 Calcite 执行计划和下推摘要")
  public Response<?> explain(@RequestBody final FederationQueryModels.SqlConsoleRequest request) {
    try {
      return Response.okay(service.explain(request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Executes a federation SQL query.
   *
   * @param request SQL console request
   * @return query response
   */
  @PostMapping("/query")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.sql-console.execute')")
  @Operation(summary = "执行只读 SQL", description = "执行联邦只读 SQL 并返回结果、执行计划和下推摘要")
  public Response<?> query(@RequestBody final FederationQueryModels.SqlConsoleRequest request) {
    try {
      return Response.okay(service.execute(request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  private static Response<String> badRequest(final String message) {
    return Response.of(
        ResponseEntity.badRequest()
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }
}
