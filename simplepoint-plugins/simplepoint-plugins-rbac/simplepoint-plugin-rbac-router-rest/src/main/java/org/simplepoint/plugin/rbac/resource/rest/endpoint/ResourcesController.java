package org.simplepoint.plugin.rbac.resource.rest.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceNode;
import org.simplepoint.security.pojo.dto.ServiceResourceRouteResult;
import org.simplepoint.security.service.ResourceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resource management endpoints.
 */
@RestController
@RequestMapping("/resources")
@Tag(name = "资源管理", description = "统一管理页面、功能、操作和接口资源")
public class ResourcesController extends BaseController<ResourceService, Resource, String> {

  public ResourcesController(ResourceService service) {
    super(service);
  }

  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('resources.view')")
  @Operation(summary = "分页查询资源", description = "根据筛选条件分页查询资源树")
  public Response<Page<ResourceNode>> limit(@RequestParam Map<String, String> attributes, Pageable pageable) {
    if (!pageable.getSort().isSorted()) {
      pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, "sort"));
    }
    return Response.limit(service.limitTree(attributes, pageable), ResourceNode.class);
  }

  @GetMapping("/children")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('resources.view')")
  @Operation(summary = "分页查询资源子节点", description = "按父资源分页查询一层资源节点，用于懒加载分配界面")
  public Response<Page<ResourceNode>> children(@RequestParam Map<String, String> attributes, Pageable pageable) {
    if (!pageable.getSort().isSorted()) {
      pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, "sort"));
    }
    return Response.limit(service.children(attributes, pageable), ResourceNode.class);
  }

  @PostMapping("/by-codes")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('resources.view')")
  @Operation(summary = "按编码查询资源", description = "根据资源编码批量查询资源明细")
  public Response<Collection<Resource>> byCodes(@RequestBody Set<String> codes) {
    return ok(service.findAllByCodes(codes));
  }

  @GetMapping("/service-routes")
  @Operation(summary = "获取用户资源路由", description = "获取当前登录用户可访问的资源路由树")
  public Response<ServiceResourceRouteResult> routes() {
    return Response.okay(service.routes());
  }

  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('resources.create')")
  @Operation(summary = "添加资源", description = "添加一个新的资源")
  public Response<Resource> add(@RequestBody Resource data) {
    return ok(service.create(data));
  }

  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('resources.edit')")
  @Operation(summary = "更新资源", description = "更新资源信息")
  public Response<Resource> modify(@RequestBody Resource data) {
    return ok(service.modifyById(data));
  }

  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('resources.delete')")
  @Operation(summary = "删除资源", description = "删除资源及其子资源")
  public Response<Set<String>> remove(@RequestParam("ids") String ids) {
    Set<String> idSet = StringUtil.stringToSet(ids);
    service.removeByIds(idSet);
    return ok(idSet);
  }
}
