package org.simplepoint.plugin.rbac.tenant.rest.controller;

import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageActivation;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageInstanceView;
import org.simplepoint.plugin.rbac.tenant.api.model.TenantPackageResourceView;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageActivationService;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantPackageQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Current-owner package operations; the selected tenant is resolved by the owning domain. */
@RestController
@RequestMapping("/tenants/current-packages")
@PreAuthorize("isAuthenticated()")
public class TenantPackageController {
  private final TenantPackageQueryService packages;
  private final TenantPackageActivationService activation;

  /** Shares the same domain implementation in every deployment topology. */
  public TenantPackageController(
      TenantPackageQueryService packages, TenantPackageActivationService activation) {
    this.packages = packages;
    this.activation = activation;
  }

  /** Returns owner-authorized list and action metadata. */
  @GetMapping("/schema")
  public Response<Map<String, Object>> schema() {
    return Response.okay(packages.schema());
  }

  /** Applies the platform query contract before pagination. */
  @GetMapping
  public Response<Page<TenantPackageInstanceView>> list(
      @RequestParam Map<String, String> filters, Pageable pageable) {
    return Response.okay(packages.query(filters, pageable));
  }

  /** Returns one tenant-owned package instance. */
  @GetMapping("/{id}")
  public Response<TenantPackageInstanceView> get(@PathVariable("id") String id) {
    return Response.okay(packages.get(id));
  }

  /** Pages purchased identities and their current accumulated balances. */
  @GetMapping("/{id}/resources")
  public Response<Page<TenantPackageResourceView>> resources(
      @PathVariable("id") String id, Pageable pageable) {
    return Response.okay(packages.resources(id, pageable));
  }

  /** Starts time exactly once; retries return the durable original activation receipt. */
  @PostMapping("/{id}/activate")
  public Response<TenantPackageActivation> activate(@PathVariable("id") String id) {
    return Response.okay(activation.activate(id));
  }
}
