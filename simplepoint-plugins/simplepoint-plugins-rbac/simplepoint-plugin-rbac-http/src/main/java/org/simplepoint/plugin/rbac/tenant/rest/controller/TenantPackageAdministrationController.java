package org.simplepoint.plugin.rbac.tenant.rest.controller;

import java.util.Map;
import java.util.Set;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.tenant.api.error.TenantPackageException;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageAssignment;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageInstanceView;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageResourceView;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageRevocation;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageActionSupplementService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAdministrationQueryService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageAssignmentService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageRevocationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform allocation/revocation is separate from the tenant owner's activation boundary. */
@RestController
@RequestMapping("/platform/tenants/{tenantId}/package-instances")
@PreAuthorize("isAuthenticated()")
public class TenantPackageAdministrationController {
  private final TenantPackageAdministrationQueryService queries;
  private final TenantPackageAssignmentService assignments;
  private final TenantPackageRevocationService revocations;
  private final TenantPackageActionSupplementService supplements;

  /** Delegates authorization and durable idempotency to identity-owned domain services. */
  public TenantPackageAdministrationController(
      TenantPackageAssignmentService assignments,
      TenantPackageRevocationService revocations,
      TenantPackageAdministrationQueryService queries,
      TenantPackageActionSupplementService supplements) {
    this.queries = queries;
    this.assignments = assignments;
    this.revocations = revocations;
    this.supplements = supplements;
  }

  /** Platform actions are checked independently of the owner-only package page. */
  @GetMapping("/schema")
  public Response<Map<String, Object>> schema(@PathVariable("tenantId") String tenantId) {
    return Response.okay(queries.schema(tenantId));
  }

  /** Server-side filters, counts and pagination within the captured tenant. */
  @GetMapping
  public Response<Page<TenantPackageInstanceView>> query(
      @PathVariable("tenantId") String tenantId,
      @RequestParam Map<String, String> filters,
      Pageable pageable) {
    return Response.okay(queries.query(tenantId, filters, pageable));
  }

  /** Reads one purchase for the selected tenant. */
  @GetMapping("/{id}")
  public Response<TenantPackageInstanceView> get(
      @PathVariable("tenantId") String tenantId, @PathVariable("id") String id) {
    return Response.okay(queries.get(tenantId, id));
  }

  /** Reads time balances without consulting platform resource definitions. */
  @GetMapping("/{id}/resources")
  public Response<Page<TenantPackageResourceView>> resources(
      @PathVariable("tenantId") String tenantId, @PathVariable("id") String id, Pageable pageable) {
    return Response.okay(queries.resources(tenantId, id, pageable));
  }

  /** The allocation ID identifies one purchase, allowing exact retries without duplicate grants. */
  public record Allocation(String allocationId, String packageCode) {}

  /** Explicit package action codes to supplement. */
  public record ActionSupplement(Set<String> resourceCodes) {}

  /** Allocates a pending instance; this operation does not start its validity period. */
  @PostMapping
  public Response<TenantPackageInstanceView> assign(
      @PathVariable("tenantId") String tenantId, @RequestBody Allocation command) {
    if (command == null) {
      throw new TenantPackageException(TenantPackageException.Code.INVALID_REQUEST);
    }
    return Response.okay(
        assignments
            .assign(
                new TenantPackageAssignment(
                    tenantId, command.packageCode(), command.allocationId()))
            .forAdministration());
  }

  /** Revokes remaining purchased time while preserving business data and role configuration. */
  @PostMapping("/{id}/revoke")
  public Response<TenantPackageRevocation> revoke(
      @PathVariable("tenantId") String tenantId, @PathVariable("id") String id) {
    return Response.okay(revocations.revoke(tenantId, id));
  }

  /** Adds admissible actions without replacing the original package or its purchase contents. */
  @PostMapping("/{id}/action-supplements")
  public Response<Void> supplement(@PathVariable("tenantId") String tenantId,
      @PathVariable("id") String id, @RequestBody ActionSupplement command) {
    supplements.supplement(tenantId, id, command.resourceCodes());
    return Response.okay();
  }
}
