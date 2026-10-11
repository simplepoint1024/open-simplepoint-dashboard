package org.simplepoint.plugin.rbac.core.rest.controller;

import java.util.Arrays;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantDirectoryItem;
import org.simplepoint.plugin.rbac.core.service.impl.TenantDirectoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Minimal tenant selection endpoints; domain service enforces membership and operation permissions. */
@RestController
@RequestMapping("/tenant-directory")
@PreAuthorize("isAuthenticated()")
public class TenantDirectoryController {

  private final TenantDirectoryService service;

  /** Creates the directory controller. */
  public TenantDirectoryController(final TenantDirectoryService service) {
    this.service = service;
  }

  /** Pages active organization users. */
  @GetMapping("/users")
  public Response<Page<TenantDirectoryItem>> users(
      @RequestParam(name = "keyword", defaultValue = "") final String keyword,
      final Pageable pageable
  ) {
    return Response.okay(service.users(keyword, pageable));
  }

  /** Resolves persisted user selections. */
  @GetMapping("/users/selected")
  public Response<List<TenantDirectoryItem>> selectedUsers(@RequestParam("values") final String values) {
    return Response.okay(service.selectedUsers(Arrays.asList(values.split(",", -1))));
  }

  /** Pages organization roles. */
  @GetMapping("/roles")
  public Response<Page<TenantDirectoryItem>> roles(
      @RequestParam(name = "keyword", defaultValue = "") final String keyword,
      final Pageable pageable
  ) {
    return Response.okay(service.roles(keyword, pageable));
  }

  /** Resolves persisted role authorities. */
  @GetMapping("/roles/selected")
  public Response<List<TenantDirectoryItem>> selectedRoles(@RequestParam("values") final String values) {
    return Response.okay(service.selectedRoles(Arrays.asList(values.split(",", -1))));
  }
}
