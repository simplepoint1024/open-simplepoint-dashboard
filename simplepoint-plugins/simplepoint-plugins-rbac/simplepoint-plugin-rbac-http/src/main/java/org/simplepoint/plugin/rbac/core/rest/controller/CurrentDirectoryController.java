package org.simplepoint.plugin.rbac.core.rest.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.service.impl.CurrentDirectoryService;
import org.simplepoint.plugin.rbac.core.service.impl.CurrentDirectoryService.Option;
import org.simplepoint.plugin.rbac.core.service.impl.CurrentDirectoryService.TenantOption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The authenticated actor, not a client-supplied user ID, owns every selection query. */
@RestController
@RequestMapping("/current-directory")
@PreAuthorize("isAuthenticated()")
public class CurrentDirectoryController {
  private final CurrentDirectoryService service;

  /** Creates the context directory controller. */
  public CurrentDirectoryController(CurrentDirectoryService service) {
    this.service = service;
  }

  /** Invalid directory budgets are client errors, without reflecting IDs or query details. */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> invalidParameters() {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_DIRECTORY_QUERY", "messageKey", "error.badRequest"));
  }

  /** Inaccessible workspaces use the same safe response for missing and foreign identities. */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, String>> denied() {
    return ResponseEntity.status(403)
        .body(Map.of("code", "DIRECTORY_ACCESS_DENIED", "messageKey", "error.forbidden"));
  }

  /** Searches switchable workspaces. */
  @GetMapping("/tenants")
  public Response<Page<TenantOption>> tenants(
      @RequestParam(name = "keyword", defaultValue = "") String keyword, Pageable pageable) {
    return Response.okay(service.tenants(keyword, pageable));
  }

  /** Resolves selected workspaces. */
  @GetMapping("/tenants/selected")
  public Response<List<TenantOption>> selectedTenants(@RequestParam("values") String values) {
    return Response.okay(service.selectedTenants(Arrays.asList(values.split(",", -1))));
  }

  /** Searches roles assigned to the current actor. */
  @GetMapping("/roles")
  public Response<Page<Option>> roles(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(name = "keyword", defaultValue = "") String keyword,
      Pageable pageable) {
    return Response.okay(service.roles(tenantId, keyword, pageable));
  }

  /** Resolves role IDs, never role authorities. */
  @GetMapping("/roles/selected")
  public Response<List<Option>> selectedRoles(
      @RequestParam("tenantId") String tenantId, @RequestParam("values") String values) {
    return Response.okay(service.selectedRoles(tenantId, Arrays.asList(values.split(",", -1))));
  }
}
