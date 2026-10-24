package org.simplepoint.plugin.rbac.core.rest.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.service.RoleResourceConfigurationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Bounded role resource configuration with explicit grant changes. */
@RestController
@RequestMapping("/role-resource-configuration/roles/{roleId}")
public class RoleResourceConfigurationController {
  private final RoleResourceConfigurationService service;

  /** Creates the resource editor endpoints. */
  public RoleResourceConfigurationController(RoleResourceConfigurationService service) {
    this.service = service;
  }

  /** Exposes only the bounded validation messages owned by this editor. */
  @org.springframework.web.bind.annotation.ExceptionHandler(
      org.springframework.web.server.ResponseStatusException.class)
  public org.springframework.http.ResponseEntity<java.util.Map<String, String>> rejected(
      org.springframework.web.server.ResponseStatusException error) {
    return org.springframework.http.ResponseEntity.status(error.getStatusCode()).body(
        java.util.Map.of("code", "RESOURCE_CONFIGURATION_REJECTED", "message",
            error.getReason() == null ? "资源配置请求失败" : error.getReason()));
  }

  /** Returns one page of resources and this role's explicit authorization state. */
  @GetMapping("/resources")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('roles.config.resource')")
  public Response<Page<RoleResourceConfigurationService.Row>> list(
      @PathVariable("roleId") String roleId,
      @RequestParam(name = "keyword", defaultValue = "") String keyword,
      @RequestParam(name = "state", defaultValue = "all") String state,
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "parentId", required = false) String parentId,
      @RequestParam(name = "rootsOnly", defaultValue = "false") boolean rootsOnly,
      @RequestParam(name = "navigationOnly", defaultValue = "false") boolean navigationOnly,
      Pageable pageable) {
    return Response.okay(service.list(roleId, keyword, state, type, parentId,
        rootsOnly, navigationOnly, pageable));
  }

  @GetMapping("/policy-options")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('roles.config.resource')")
  public Response<Page<RoleResourceConfigurationService.PolicyOption>> policies(
      @PathVariable("roleId") String roleId,
      @RequestParam("resourceCode") String resourceCode,
      @RequestParam("kind") String kind,
      @RequestParam(name = "keyword", defaultValue = "") String keyword,
      @RequestParam(name = "selectedId", required = false) String selectedId,
      Pageable pageable) {
    return Response.okay(service.policies(roleId, resourceCode, kind, keyword, pageable, selectedId));
  }

  /** Atomically applies explicit resource changes without replacing off-page grants. */
  @PutMapping("/resource-changes")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('Administrator') or hasAuthority('roles.config.resource')")
  public void save(@PathVariable("roleId") String roleId,
      @RequestBody RoleResourceConfigurationService.Patch patch) {
    service.save(roleId, patch);
  }

  /** Previews the entire visible subtree, including unloaded pages. */
  @GetMapping("/resource-subtree")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('roles.config.resource')")
  public Response<RoleResourceConfigurationService.BulkPreview> previewSubtree(
      @PathVariable("roleId") String roleId,
      @RequestParam("resourceId") String resourceId,
      @RequestParam("granted") boolean granted) {
    return Response.okay(service.previewSubtree(roleId, resourceId, granted));
  }

  /** Loads the complete editable subtree before staging a directory checkbox change. */
  @GetMapping("/resource-subtree/resources")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('roles.config.resource')")
  public Response<java.util.List<RoleResourceConfigurationService.Row>> subtreeRows(
      @PathVariable("roleId") String roleId,
      @RequestParam("resourceId") String resourceId) {
    return Response.okay(service.subtreeRows(roleId, resourceId));
  }

  /** Resolves visible ancestor context without loading the entire resource catalog. */
  @PostMapping("/resource-context")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('roles.config.resource')")
  public Response<java.util.List<RoleResourceConfigurationService.Row>> contextRows(
      @PathVariable("roleId") String roleId, @RequestBody java.util.List<String> resourceIds) {
    return Response.okay(service.contextRows(roleId, resourceIds));
  }

  /** Atomically grants or revokes the previewed resource subtree. */
  @PutMapping("/resource-subtree")
  @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('Administrator') or hasAuthority('roles.config.resource')")
  public void saveSubtree(@PathVariable("roleId") String roleId,
      @RequestBody RoleResourceConfigurationService.BulkCommand command) {
    service.saveSubtree(roleId, command);
  }
}
