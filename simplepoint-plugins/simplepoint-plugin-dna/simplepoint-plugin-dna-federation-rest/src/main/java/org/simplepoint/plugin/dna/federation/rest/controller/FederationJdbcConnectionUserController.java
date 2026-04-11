package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationJdbcConnectionUser;
import org.simplepoint.plugin.dna.federation.api.pojo.dto.FederationJdbcUserDataSourceAssignDto;
import org.simplepoint.plugin.dna.federation.api.service.FederationJdbcConnectionUserService;
import org.simplepoint.plugin.dna.federation.api.vo.FederationJdbcUserDataSourceItemVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * JDBC connection-user maintenance endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.JDBC_USERS, DnaFederationPaths.PLATFORM_JDBC_USERS})
@Tag(name = "JDBC连接用户管理", description = "用于维护 DNA JDBC 驱动可连接的系统用户")
public class FederationJdbcConnectionUserController
    extends BaseController<FederationJdbcConnectionUserService, FederationJdbcConnectionUser, String> {

  /**
   * Creates a JDBC connection-user controller.
   *
   * @param service service
   */
  public FederationJdbcConnectionUserController(final FederationJdbcConnectionUserService service) {
    super(service);
  }

  /**
   * Pages JDBC connection-user grants.
   *
   * @param attributes query filters
   * @param pageable pageable
   * @return paged grants
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.view')")
  @Operation(summary = "分页查询 JDBC 连接用户", description = "根据条件分页查询 DNA JDBC 连接用户授权配置")
  public Response<Page<FederationJdbcConnectionUser>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), FederationJdbcConnectionUser.class);
  }

  /**
   * Pages datasource options for one-user multi-datasource assignment.
   *
   * @param pageable pageable
   * @return paged datasource items
   */
  @GetMapping("/items")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.view')")
  @Operation(summary = "分页查询可分配数据源", description = "返回 JDBC 连接用户授权页左侧可分配的数据源分页")
  public Response<Page<FederationJdbcUserDataSourceItemVo>> items(final Pageable pageable) {
    return ok(service.dataSourceItems(pageable));
  }

  /**
   * Returns datasource items for the supplied ids.
   *
   * @param dataSourceIds datasource ids
   * @return datasource items
   */
  @PostMapping("/selected-items")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.view')")
  @Operation(summary = "查询已选数据源明细", description = "根据数据源 ID 列表返回 JDBC 用户授权页右侧需要展示的数据源明细")
  public Response<Collection<FederationJdbcUserDataSourceItemVo>> selectedItems(
      @RequestBody final List<String> dataSourceIds
  ) {
    return ok(service.selectedDataSourceItems(dataSourceIds));
  }

  /**
   * Returns datasource ids currently authorized for a user.
   *
   * @param userId user id
   * @return datasource ids
   */
  @GetMapping("/authorized")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.view')")
  @Operation(summary = "查询用户已授权数据源", description = "根据系统用户 ID 查询已授权的数据源 ID 列表")
  public Response<Collection<String>> authorized(@RequestParam("userId") final String userId) {
    return ok(service.authorized(userId));
  }

  /**
   * Returns enabled grants with their operation permissions for a user.
   *
   * @param userId user id
   * @return enabled grants
   */
  @GetMapping("/grants")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.view')")
  @Operation(summary = "查询用户授权详情", description = "根据系统用户 ID 查询启用的授权记录（含操作权限）")
  public Response<Collection<FederationJdbcConnectionUser>> grants(
      @RequestParam("userId") final String userId
  ) {
    return ok(service.enabledGrants(userId));
  }

  /**
   * Updates the operation permissions of an existing grant.
   *
   * @param grantId grant id
   * @param permissions new set of operation permission codes (METADATA / QUERY / EXPLAIN / DDL / DML)
   * @return updated grant
   */
  @PutMapping("/permissions")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.edit')")
  @Operation(summary = "修改授权操作权限", description = "修改单条 JDBC 连接用户授权记录的操作权限集合")
  public Response<?> updatePermissions(
      @RequestParam("grantId") final String grantId,
      @RequestBody final Set<String> permissions
  ) {
    try {
      var grant = service.findById(grantId).orElse(null);
      if (grant == null) {
        return badRequest("授权记录不存在: " + grantId);
      }
      grant.setOperationPermissions(permissions);
      return ok(service.modifyById(grant));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Authorizes datasources for a user.
   *
   * @param dto datasource assignment payload
   * @return created or existing grants
   */
  @PostMapping("/authorize")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.edit') or hasAuthority('dna.federation.jdbc-users.create')")
  @Operation(summary = "批量授权用户数据源", description = "为一个系统用户批量分配可访问的 DNA 数据源")
  public Response<?> authorize(@RequestBody final FederationJdbcUserDataSourceAssignDto dto) {
    try {
      return ok(service.authorize(dto));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Removes datasource authorization for a user.
   *
   * @param dto datasource assignment payload
   * @return empty response
   */
  @PostMapping("/unauthorized")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.edit') or hasAuthority('dna.federation.jdbc-users.delete')")
  @Operation(summary = "批量取消用户数据源授权", description = "为一个系统用户批量移除 DNA 数据源访问授权")
  public Response<?> unauthorized(@RequestBody final FederationJdbcUserDataSourceAssignDto dto) {
    try {
      service.unauthorized(dto);
      return Response.okay();
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Creates a JDBC connection-user grant.
   *
   * @param data grant
   * @return created grant
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.create')")
  @Operation(summary = "新增 JDBC 连接用户", description = "新增一个 DNA JDBC 驱动连接授权配置")
  public Response<?> add(@RequestBody final FederationJdbcConnectionUser data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a JDBC connection-user grant.
   *
   * @param data grant
   * @return updated grant
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.edit')")
  @Operation(summary = "修改 JDBC 连接用户", description = "修改一个已存在的 DNA JDBC 驱动连接授权配置")
  public Response<?> modify(@RequestBody final FederationJdbcConnectionUser data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes JDBC connection-user grants.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.jdbc-users.delete')")
  @Operation(summary = "删除 JDBC 连接用户", description = "根据 ID 集合删除 DNA JDBC 驱动连接授权配置")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return ok(idSet);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  private static Response<String> badRequest(final String message) {
    return Response.of(
        ResponseEntity.badRequest()
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }
}
