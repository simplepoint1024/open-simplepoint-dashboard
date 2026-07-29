package org.simplepoint.plugin.ai.runtime.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.runtime.api.constants.AiRuntimePaths;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolScaleRequest;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimePoolUpsertRequest;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimePoolService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Current-scope desired-state API for reusable OCI MCP runtime pools.
 */
@RestController
@RequestMapping(AiRuntimePaths.POOLS)
@Tag(name = "AI Runtime Pool", description = "OCI MCP 弹性副本池")
public class AiRuntimePoolController {

  private final AiRuntimePoolService service;

  /**
   * Creates the runtime pool management controller.
   */
  public AiRuntimePoolController(final AiRuntimePoolService service) {
    this.service = service;
  }

  /**
   * Pages runtime pools in the active platform or tenant scope.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "分页查询 OCI Runtime 副本池")
  public Response<Page<AiRuntimePool>> findAll(final Pageable pageable) {
    return Response.limit(service.findAll(pageable), AiRuntimePool.class);
  }

  /**
   * Returns one readable runtime pool.
   */
  @GetMapping("/{poolId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "查询 OCI Runtime 副本池")
  public Response<AiRuntimePool> find(
      @PathVariable("poolId") final String poolId
  ) {
    try {
      return service.find(poolId)
          .map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Returns the current-scope pool bound to one managed MCP server.
   */
  @GetMapping("/by-server/{serverId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "按 MCP Server 查询 OCI Runtime 副本池")
  public Response<AiRuntimePool> findByServer(
      @PathVariable("serverId") final String serverId
  ) {
    try {
      return service.findByServer(serverId)
          .map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Creates or updates current-scope pool desired state.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "创建或更新 OCI Runtime 副本池")
  public Response<AiRuntimePool> upsert(
      @RequestBody final RuntimePoolUpsertRequest request
  ) {
    try {
      return Response.okay(service.upsert(request));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Changes explicit desired replicas.
   */
  @PostMapping("/{poolId}/scale")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "调整 OCI Runtime 副本池目标副本")
  public Response<AiRuntimePool> scale(
      @PathVariable("poolId") final String poolId,
      @RequestBody final RuntimePoolScaleRequest request
  ) {
    try {
      return Response.okay(service.scale(poolId, request));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Refreshes activity and restores activation capacity.
   */
  @PostMapping("/{poolId}/activate")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "激活 OCI Runtime 副本池并刷新空闲计时")
  public Response<AiRuntimePool> activate(
      @PathVariable("poolId") final String poolId
  ) {
    try {
      return Response.okay(service.activate(poolId));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Disables a pool and requests complete replica cleanup.
   */
  @PostMapping("/{poolId}/disable")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "禁用 OCI Runtime 副本池并回收全部副本")
  public Response<AiRuntimePool> disable(
      @PathVariable("poolId") final String poolId
  ) {
    try {
      return Response.okay(service.disable(poolId));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Replaces every active replica with the current immutable pool definition.
   */
  @PostMapping("/{poolId}/redeploy")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "重新部署 OCI Runtime 副本池")
  public Response<AiRuntimePool> redeploy(
      @PathVariable("poolId") final String poolId
  ) {
    try {
      return Response.okay(service.redeploy(poolId));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Deletes a disabled pool after its workloads have been fully reclaimed.
   */
  @DeleteMapping("/{poolId}")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.pools.manage')"
  )
  @Operation(summary = "删除已回收的 OCI Runtime 副本池")
  public Response<String> remove(
      @PathVariable("poolId") final String poolId
  ) {
    try {
      service.remove(poolId);
      return Response.okay(poolId);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  private ResponseStatusException badRequest(
      final IllegalArgumentException exception
  ) {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        exception.getMessage(),
        exception
    );
  }
}
