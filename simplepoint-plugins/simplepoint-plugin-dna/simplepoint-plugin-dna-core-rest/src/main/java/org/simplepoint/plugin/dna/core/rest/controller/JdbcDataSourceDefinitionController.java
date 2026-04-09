package org.simplepoint.plugin.dna.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.core.api.constants.DnaPaths;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDataSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDataSourceDefinitionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managed datasource definition endpoints.
 */
@RestController
@RequestMapping({DnaPaths.DATA_SOURCES, DnaPaths.PLATFORM_DATA_SOURCES})
@Tag(name = "JDBC数据源管理", description = "用于管理 JDBC 数据源定义与连接测试")
public class JdbcDataSourceDefinitionController
    extends BaseController<JdbcDataSourceDefinitionService, JdbcDataSourceDefinition, String> {

  /**
   * Creates a datasource definition controller.
   *
   * @param service datasource definition service
   */
  public JdbcDataSourceDefinitionController(final JdbcDataSourceDefinitionService service) {
    super(service);
  }

  /**
   * Pages datasource definitions.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged datasource definitions
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-sources.view')")
  @Operation(summary = "分页查询数据源", description = "根据条件分页查询 JDBC 数据源定义")
  public Response<Page<JdbcDataSourceDefinition>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), JdbcDataSourceDefinition.class);
  }

  /**
   * Creates a datasource definition.
   *
   * @param data datasource definition
   * @return created datasource definition
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-sources.create')")
  @Operation(summary = "新增数据源", description = "新增一个 JDBC 数据源定义")
  public Response<?> add(@RequestBody final JdbcDataSourceDefinition data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a datasource definition.
   *
   * @param data datasource definition
   * @return updated datasource definition
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-sources.edit')")
  @Operation(summary = "修改数据源", description = "修改一个已存在的 JDBC 数据源定义")
  public Response<?> modify(@RequestBody final JdbcDataSourceDefinition data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes datasource definitions by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-sources.delete')")
  @Operation(summary = "删除数据源", description = "根据 ID 集合删除数据源定义")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return ok(idSet);
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Tests the datasource connection.
   *
   * @param id datasource id
   * @return connection result
   */
  @PostMapping("/{id}/connect")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-sources.connect')")
  @Operation(summary = "测试连接", description = "根据数据源定义构建 SimpleDataSource 并测试数据库连接")
  public Response<?> connect(@PathVariable("id") final String id) {
    try {
      return ok(service.connect(id));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Creates a bad-request response with plain-text payload.
   *
   * @param message error message
   * @return bad-request response
   */
  private Response<String> badRequest(final String message) {
    return Response.of(
        ResponseEntity.badRequest()
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }
}
