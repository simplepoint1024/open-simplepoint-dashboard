package org.simplepoint.plugin.rbac.tenant.rest.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantLegacyAdoption;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantLegacyAdoptionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit platform maintenance adapter; the domain revalidates platform administrator scope. */
@RestController
@RequestMapping("/platform/tenants/{tenantId}/resource-adoption")
@PreAuthorize("isAuthenticated()")
public class TenantLegacyAdoptionController {
  private final TenantLegacyAdoptionService adoption;

  /** Delegates all persistence and transaction ownership to the identity domain. */
  public TenantLegacyAdoptionController(TenantLegacyAdoptionService adoption) {
    this.adoption = adoption;
  }

  /** Previews the current legacy facts without importing resources or issuing credits. */
  @GetMapping("/preview")
  public Response<TenantLegacyAdoption.Preview> preview(@PathVariable("tenantId") String tenantId) {
    return Response.okay(adoption.preview(tenantId));
  }

  /** Re-reads current facts and atomically adopts them, returning the durable original receipt on retry. */
  @PostMapping
  public Response<TenantLegacyAdoption> adopt(@PathVariable("tenantId") String tenantId) {
    return Response.okay(adoption.adopt(tenantId));
  }
}
