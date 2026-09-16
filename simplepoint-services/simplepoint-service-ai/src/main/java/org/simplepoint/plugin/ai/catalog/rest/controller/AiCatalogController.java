package org.simplepoint.plugin.ai.catalog.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.catalog.api.constants.AiCatalogPaths;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogErrorCode;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogImportRequest;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogRuntimeImportRequest;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncErrorCode;
import org.simplepoint.plugin.ai.catalog.api.model.AiCatalogSyncResult;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogPackageKind;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSource;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncMode;
import org.simplepoint.plugin.ai.catalog.api.model.CatalogSyncStatus;
import org.simplepoint.plugin.ai.catalog.api.service.AiCatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI workbench extension market endpoints.
 */
@RestController
@RequestMapping(AiCatalogPaths.CATALOG)
@Tag(name = "AI extension catalog", description = "内部扩展市场和官方 MCP Registry")
@Slf4j
public class AiCatalogController {

  private final AiCatalogService service;

  /**
   * Creates the controller.
   */
  public AiCatalogController(final AiCatalogService service) {
    this.service = service;
  }

  /**
   * Pages internal and official extensions visible in the active scope.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.catalog.view')"
  )
  @Operation(summary = "分页查询 AI 扩展市场")
  public Response<?> findAll(
      @RequestParam(name = "query", required = false) final String query,
      @RequestParam(name = "source", required = false) final CatalogSource source,
      @RequestParam(name = "kind", required = false) final CatalogPackageKind kind,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size
  ) {
    return invoke(
        () -> service.findAll(query, source, kind, page, size)
    );
  }

  /**
   * Returns durable official Registry cursor and health.
   */
  @GetMapping("/sync-status")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.catalog.view')"
  )
  @Operation(summary = "查询官方 MCP Registry 同步状态")
  public Response<?> syncStatus() {
    return invoke(service::getOfficialSyncState);
  }

  /**
   * Runs a bounded platform-only synchronization pass.
   */
  @PostMapping("/sync")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.catalog.sync')"
  )
  @Operation(summary = "同步官方 MCP Registry")
  public Response<?> sync() {
    try {
      return Response.okay(service.syncOfficialRegistry());
    } catch (RuntimeException ex) {
      log.error("Official MCP Registry endpoint synchronization failed", ex);
      return Response.okay(new AiCatalogSyncResult(
          CatalogSource.OFFICIAL_MCP,
          CatalogSyncMode.INCREMENTAL,
          CatalogSyncStatus.FAILED,
          0,
          null,
          null,
          AiCatalogSyncErrorCode.OFFICIAL_MCP_SYNC_FAILED
      ));
    }
  }

  /**
   * Imports one safe remote MCP registration into the active scope.
   */
  @PostMapping("/{entryId}/import")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.catalog.import')"
  )
  @Operation(summary = "导入官方远程 MCP Server")
  public Response<?> importServer(
      @PathVariable("entryId") final String entryId,
      @RequestBody(required = false) final AiCatalogImportRequest request
  ) {
    return invoke(
        () -> service.importOfficialMcpServer(entryId, request)
    );
  }

  /** Imports one official package as a normalized Runtime Profile draft. */
  @PostMapping("/{entryId}/import-runtime")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.catalog.import')"
  )
  @Operation(summary = "导入官方 MCP package 为 Runtime Profile 草稿")
  public Response<?> importRuntimeProfile(
      @PathVariable("entryId") final String entryId,
      @RequestBody(required = false)
      final AiCatalogRuntimeImportRequest request
  ) {
    return invoke(
        () -> service.importOfficialMcpPackage(entryId, request)
    );
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return Response.okay(operation.get());
    } catch (IllegalArgumentException ex) {
      log.warn("AI catalog request was rejected", ex);
      return catalogError(
          HttpStatus.BAD_REQUEST,
          AiCatalogErrorCode.AI_CATALOG_REQUEST_INVALID
      );
    } catch (IllegalStateException ex) {
      log.error("AI catalog operation is unavailable", ex);
      return catalogError(
          HttpStatus.BAD_REQUEST,
          AiCatalogErrorCode.AI_CATALOG_OPERATION_UNAVAILABLE
      );
    } catch (AccessDeniedException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      log.error("Unhandled AI catalog operation failure", ex);
      return catalogError(
          HttpStatus.INTERNAL_SERVER_ERROR,
          AiCatalogErrorCode.AI_CATALOG_OPERATION_FAILED
      );
    }
  }

  private Response<Map<String, String>> catalogError(
      final HttpStatus status,
      final AiCatalogErrorCode errorCode
  ) {
    return Response.of(
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("errorCode", errorCode.name()))
    );
  }
}
