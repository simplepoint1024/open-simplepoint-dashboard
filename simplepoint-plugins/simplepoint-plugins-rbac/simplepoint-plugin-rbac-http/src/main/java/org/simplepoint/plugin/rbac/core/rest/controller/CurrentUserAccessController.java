package org.simplepoint.plugin.rbac.core.rest.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.service.CurrentUserAccessService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only personal access information; callers never select another user. */
@RestController
@RequestMapping("/users/me")
public class CurrentUserAccessController {
  private final CurrentUserAccessService service;

  public CurrentUserAccessController(CurrentUserAccessService service) {
    this.service = service;
  }

  @GetMapping("/access/resources")
  @PreAuthorize("isAuthenticated()")
  public Response<Page<CurrentUserAccessService.ResourceAccess>> resources(
      @RequestParam(name = "roleId", required = false) String roleId,
      @RequestParam(name = "keyword", defaultValue = "") String keyword,
      @RequestParam(name = "type", defaultValue = "ALL") String type,
      Pageable pageable) {
    return Response.okay(service.resources(roleId, keyword, type, pageable));
  }

  @GetMapping("/access/page-detail")
  @PreAuthorize("isAuthenticated()")
  public Response<CurrentUserAccessService.PageAccessDetail> pageDetail(
      @RequestParam(name = "roleId", required = false) String roleId,
      @RequestParam("code") String code) {
    return Response.okay(service.pageDetail(roleId, code));
  }

  @GetMapping("/organization")
  @PreAuthorize("isAuthenticated()")
  public Response<CurrentUserAccessService.OrganizationInfo> organization() {
    return Response.okay(service.organization());
  }
}
