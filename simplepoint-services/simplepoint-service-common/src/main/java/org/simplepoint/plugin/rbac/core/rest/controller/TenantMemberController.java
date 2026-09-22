package org.simplepoint.plugin.rbac.core.rest.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantMemberView;
import org.simplepoint.plugin.rbac.core.api.service.TenantMemberService;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@org.simplepoint.core.permission.PermissionPage(value="users.view", model=TenantMemberView.class)
@RestController
@RequestMapping("/tenant-members")
@PreAuthorize("isAuthenticated()")
public class TenantMemberController {
  private final TenantMemberService service;
  private final org.simplepoint.core.schema.AnnotatedTableSchemaProvider schemas;
  public TenantMemberController(TenantMemberService service,
      org.simplepoint.api.security.service.DetailsProviderService details) {
    this.service = service;
    this.schemas = new org.simplepoint.core.schema.AnnotatedTableSchemaProvider(details);
  }
  @GetMapping("/capabilities")
  public Response<java.util.Map<String, Boolean>> capabilities() { return Response.okay(service.capabilities()); }
  @GetMapping
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Page<java.util.Map<String, Object>>> list(@RequestParam java.util.Map<String, String> filters, Pageable page) {
    return Response.okay(service.list(filters, page).map(TenantMemberView::project));
  }
  @GetMapping("/by-organization")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<Page<java.util.Map<String, Object>>> byOrganization(
      @RequestParam("orgId") String orgId,
      @RequestParam(name = "keyword", defaultValue = "") String keyword, Pageable page) {
    return Response.okay(service.organizationMembers(orgId, keyword, page).map(TenantMemberView::project));
  }
  @GetMapping("/organization-candidates")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<Page<java.util.Map<String, Object>>> organizationCandidates(
      @RequestParam("orgId") String orgId,
      @RequestParam(name = "keyword", defaultValue = "") String keyword, Pageable page) {
    return Response.okay(service.organizationCandidates(orgId, keyword, page).map(TenantMemberView::project));
  }
  @GetMapping("/schema")
  @PreAuthorize("hasAuthority('users.view')")
  public Response<java.util.Map<String, Object>> schema() {
    service.capabilities();
    return Response.okay(schemas.schema(TenantMemberView.class, org.simplepoint.core.AuthorizationContextHolder.getContext()));
  }
  @PostMapping
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Void> add(@RequestBody TenantMemberCommand command) { service.add(command); return Response.okay(null); }
  @PutMapping("/{userId}")
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Void> update(@PathVariable("userId") String userId, @RequestBody TenantMemberCommand command) {
    service.update(userId, command); return Response.okay(null);
  }
  @PutMapping("/by-organization/{userId}")
  @PreAuthorize("hasAuthority('organizations.view')")
  public Response<Void> updateOrganizationMember(@PathVariable("userId") String userId,
      @RequestBody TenantMemberCommand command) {
    service.update(userId, command); return Response.okay(null);
  }
  @DeleteMapping("/{userId}")
  @PreAuthorize("hasAuthority('users.view')")
  public Response<Void> remove(@PathVariable("userId") String userId) { service.remove(userId); return Response.okay(null); }
}
