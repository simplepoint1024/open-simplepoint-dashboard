package org.simplepoint.plugin.ai.runtime.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.runtime.api.constants.AiRuntimePaths;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeWorkloadSubmitRequest;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeWorkloadService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Current-scope management and test API for OCI MCP workloads.
 */
@RestController
@RequestMapping(AiRuntimePaths.WORKLOADS)
@Tag(name = "AI Runtime Workload", description = "OCI MCP 工作负载调度")
public class AiRuntimeWorkloadController {

  private final AiRuntimeWorkloadService service;

  /**
   * Creates the workload management controller.
   */
  public AiRuntimeWorkloadController(
      final AiRuntimeWorkloadService service
  ) {
    this.service = service;
  }

  /**
   * Pages workloads in the current platform or tenant context.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')"
  )
  @Operation(summary = "分页查询 OCI Runtime 工作负载")
  public Response<Page<AiRuntimeWorkload>> findAll(
      final Pageable pageable
  ) {
    return Response.limit(service.findAll(pageable), AiRuntimeWorkload.class);
  }

  /**
   * Returns one workload in the current platform or tenant context.
   */
  @GetMapping("/{workloadId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')"
  )
  @Operation(summary = "查询 OCI Runtime 工作负载")
  public Response<AiRuntimeWorkload> find(
      @PathVariable("workloadId") final String workloadId
  ) {
    try {
      return service.find(workloadId)
          .map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Lists workloads owned by one pool in the current management scope.
   */
  @GetMapping("/by-pool/{poolId}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')"
  )
  @Operation(summary = "按 Runtime Pool 查询 OCI Workload")
  public Response<List<AiRuntimeWorkload>> findByPool(
      @PathVariable("poolId") final String poolId
  ) {
    try {
      return Response.okay(service.findByPool(poolId));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Submits an idempotent digest-pinned workload.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.workloads.manage')"
  )
  @Operation(summary = "提交 OCI Runtime 工作负载")
  public Response<AiRuntimeWorkload> submit(
      @RequestBody final RuntimeWorkloadSubmitRequest request
  ) {
    try {
      return Response.okay(service.submit(request));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Requests a fenced stop and cleanup.
   */
  @PostMapping("/{workloadId}/stop")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.workloads.manage')"
  )
  @Operation(summary = "停止 OCI Runtime 工作负载")
  public Response<AiRuntimeWorkload> stop(
      @PathVariable("workloadId") final String workloadId
  ) {
    try {
      return Response.okay(service.stop(workloadId));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  private ResponseStatusException badRequest(
      final IllegalArgumentException exception
  ) {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "AI_RUNTIME_WORKLOAD_REQUEST_INVALID",
        exception
    );
  }
}
