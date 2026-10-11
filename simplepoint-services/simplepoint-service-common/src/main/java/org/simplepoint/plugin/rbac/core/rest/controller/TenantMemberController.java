package org.simplepoint.plugin.rbac.core.rest.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantMemberView;
import org.simplepoint.plugin.rbac.core.api.service.TenantMemberService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Member HTTP contracts with service-enforced authenticated workspace ownership. */
@org.simplepoint.core.permission.PermissionPage(
    value = "users.view",
    model = TenantMemberView.class)
@RestController
@RequestMapping("/tenant-members")
@PreAuthorize("isAuthenticated()")
public class TenantMemberController {
  private final TenantMemberService service;
  private final org.simplepoint.core.schema.AnnotatedTableSchemaProvider schemas;

  /** Creates the member controller and permission-aware schema provider. */
  public TenantMemberController(
      TenantMemberService service,
      org.simplepoint.api.security.service.DetailsProviderService details) {
    this.service = service;
    this.schemas = new org.simplepoint.core.schema.AnnotatedTableSchemaProvider(details);
  }

  /** Returns actual member-management capabilities. */
  @GetMapping("/capabilities")
  public Response<java.util.Map<String, Boolean>> capabilities() {
    return Response.okay(service.capabilities());
  }

  /** Queries visible members in the current tenant. */
  @GetMapping
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Page<java.util.Map<String, Object>>> list(
      @RequestParam java.util.Map<String, String> filters, Pageable page) {
    return Response.okay(service.list(filters, page).map(TenantMemberView::project));
  }

  /** Queries members of an authorized organization subtree. */
  @GetMapping("/by-organization")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<Page<java.util.Map<String, Object>>> byOrganization(
      @RequestParam("orgId") String orgId,
      @RequestParam java.util.Map<String, String> filters,
      Pageable page) {
    return Response.okay(
        service.queryOrganizationMembers(orgId, filters, page).map(TenantMemberView::project));
  }

  /** Queries eligible candidates for the captured organization. */
  @GetMapping("/organization-candidates")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<Page<java.util.Map<String, Object>>> organizationCandidates(
      @RequestParam("orgId") String orgId,
      @RequestParam java.util.Map<String, String> filters,
      Pageable page) {
    return Response.okay(
        service.queryOrganizationCandidates(orgId, filters, page).map(TenantMemberView::project));
  }

  /** Returns the member schema with field policy applied. */
  @GetMapping("/schema")
  @PreAuthorize("hasAuthority('users.view')")
  public Response<java.util.Map<String, Object>> schema() {
    service.capabilities();
    return Response.okay(
        schemas.schema(
            TenantMemberView.class, org.simplepoint.core.AuthorizationContextHolder.getContext()));
  }

  /** Returns capabilities for the selected organization. */
  @GetMapping("/by-organization/capabilities")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<java.util.Map<String, Boolean>> organizationCapabilities() {
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    boolean manage = Boolean.TRUE.equals(service.capabilities().get("manage"));
    boolean remove =
        context != null
            && (Boolean.TRUE.equals(context.getIsAdministrator())
                || context.getResources().contains("organizations.members.remove"));
    return Response.okay(java.util.Map.of("manage", manage, "remove", manage && remove));
  }

  /** Returns the organization-member query and field-access contract. */
  @GetMapping("/by-organization/schema")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<java.util.Map<String, Object>> organizationSchema() {
    service.capabilities();
    var schema = schemas.jsonSchema(TenantMemberView.class);
    schema.withObject("x-query").put("maxPageSize", 100);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    return Response.okay(
        java.util.Map.of(
            "schema",
            mapper.convertValue(
                schema,
                new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.Map<String, Object>>() {}),
            "buttons",
            java.util.List.of()));
  }

  /** Removes organization membership without deleting the global user. */
  @DeleteMapping("/by-organization/{userId}")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('organizations.members.remove')")
  public Response<Void> removeOrganizationMember(
      @PathVariable("userId") String userId,
      @RequestBody
          org.simplepoint.plugin.rbac.core.api.pojo.command.OrganizationMemberRemoval command) {
    service.removeFromOrganization(userId, command);
    return Response.okay(null);
  }

  /** Adds a permitted member in the current workspace. */
  @PostMapping
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Void> add(@RequestBody TenantMemberCommand command) {
    service.add(command);
    return Response.okay(null);
  }

  /** Updates tenant membership with revision protection. */
  @PutMapping("/{userId}")
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Void> update(
      @PathVariable("userId") String userId, @RequestBody TenantMemberCommand command) {
    service.update(userId, command);
    return Response.okay(null);
  }

  /** Updates membership inside the captured organization boundary. */
  @PutMapping("/by-organization/{userId}")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<Void> updateOrganizationMember(
      @PathVariable("userId") String userId, @RequestBody TenantMemberCommand command) {
    service.update(userId, command);
    return Response.okay(null);
  }

  /** Removes membership after service-level authorization. */
  @DeleteMapping("/{userId}")
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Void> remove(@PathVariable("userId") String userId) {
    service.remove(userId);
    return Response.okay(null);
  }

  /** Maps stale revisions to a safe conflict response. */
  @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
  public org.springframework.http.ResponseEntity<java.util.Map<String, String>> staleMember(
      org.springframework.dao.OptimisticLockingFailureException error) {
    return org.springframework.http.ResponseEntity.status(409)
        .body(java.util.Map.of("code", "MEMBER_CHANGED", "message", "成员已发生变更，请刷新后重试"));
  }
}
