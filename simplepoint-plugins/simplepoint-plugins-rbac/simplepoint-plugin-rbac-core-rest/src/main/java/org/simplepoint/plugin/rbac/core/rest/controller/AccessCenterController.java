package org.simplepoint.plugin.rbac.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.pojo.dto.AccessCenterRoleAuthorizationDto;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleDetailVo;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.AccessCenterRoleOverviewVo;
import org.simplepoint.plugin.rbac.core.api.service.AccessCenterService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Access-control workbench endpoints.
 */
@RestController
@RequestMapping("/access-center")
@Tag(name = "权限中心", description = "统一角色授权与权限影响范围查询")
public class AccessCenterController {

  private final AccessCenterService service;

  public AccessCenterController(AccessCenterService service) {
    this.service = service;
  }

  @GetMapping("/roles")
  @Operation(summary = "角色授权概览", description = "分页获取角色、权限数量、用户数量与权限范围摘要")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('access-center.view') or hasAuthority('roles.config.permission')")
  public Response<Page<AccessCenterRoleOverviewVo>> roleOverviews(Pageable pageable) {
    return Response.okay(service.roleOverviews(pageable));
  }

  @GetMapping("/roles/{roleId}")
  @Operation(summary = "角色授权详情", description = "获取角色已授权权限、数据权限、字段权限和影响用户")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('access-center.view') or hasAuthority('roles.config.permission')")
  public Response<AccessCenterRoleDetailVo> roleDetail(@PathVariable String roleId) {
    return Response.okay(service.roleDetail(roleId));
  }

  @PutMapping("/roles/{roleId}/authorization")
  @Operation(summary = "保存角色授权", description = "统一保存角色权限、数据权限和字段权限")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('access-center.manage') or hasAuthority('roles.config.permission')")
  public Response<AccessCenterRoleDetailVo> saveRoleAuthorization(
      @PathVariable String roleId,
      @RequestBody AccessCenterRoleAuthorizationDto dto
  ) {
    if (dto == null) {
      dto = new AccessCenterRoleAuthorizationDto();
    }
    dto.setRoleId(roleId);
    return Response.okay(service.saveRoleAuthorization(dto));
  }
}
