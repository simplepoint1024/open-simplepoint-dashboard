package org.simplepoint.plugin.ai.runtime.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.runtime.api.constants.AiRuntimePaths;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeSecret;
import org.simplepoint.plugin.ai.runtime.api.model.RuntimeSecretRotateRequest;
import org.simplepoint.plugin.ai.runtime.api.service.AiRuntimeSecretService;
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
 * Current-scope management API for encrypted Runtime secret references.
 */
@RestController
@RequestMapping(AiRuntimePaths.SECRETS)
@Tag(name = "AI Runtime Secret", description = "OCI 工作负载短期 Secret")
public class AiRuntimeSecretController {

  private final AiRuntimeSecretService service;

  /**
   * Creates the current-scope Runtime secret controller.
   */
  public AiRuntimeSecretController(final AiRuntimeSecretService service) {
    this.service = service;
  }

  /**
   * Pages secret metadata without returning encrypted or plaintext values.
   */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.secrets.manage') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')"
  )
  @Operation(summary = "分页查询 Runtime Secret")
  public Response<Page<AiRuntimeSecret>> findAll(final Pageable pageable) {
    return Response.limit(service.findAll(pageable), AiRuntimeSecret.class);
  }

  /**
   * Finds one secret metadata record in the current scope.
   */
  @GetMapping("/{id}")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.secrets.manage') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')"
  )
  @Operation(summary = "查询 Runtime Secret")
  public Response<AiRuntimeSecret> find(
      @PathVariable("id") final String id
  ) {
    try {
      return service.find(id)
          .map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Creates an encrypted Runtime secret reference.
   */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.secrets.manage')"
  )
  @Operation(summary = "创建加密 Runtime Secret")
  public Response<AiRuntimeSecret> create(
      @RequestBody final AiRuntimeSecret secret
  ) {
    try {
      return Response.okay(service.create(secret));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Rotates the encrypted material for an existing reference.
   */
  @PostMapping("/{id}/rotate")
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.secrets.manage')"
  )
  @Operation(summary = "轮换 Runtime Secret")
  public Response<AiRuntimeSecret> rotate(
      @PathVariable("id") final String id,
      @RequestBody final RuntimeSecretRotateRequest request
  ) {
    try {
      return Response.okay(service.rotate(
          id,
          request == null ? null : request.value()
      ));
    } catch (IllegalArgumentException ex) {
      throw badRequest(ex);
    }
  }

  /**
   * Soft-deletes the selected secret references.
   */
  @DeleteMapping
  @PreAuthorize(
      "hasRole('Administrator') or "
          + "hasAuthority('ai.workbench.runtime.secrets.manage')"
  )
  @Operation(summary = "删除 Runtime Secret")
  public Response<Void> remove(
      @RequestBody final Collection<String> ids
  ) {
    try {
      service.remove(ids);
      return Response.okay();
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
