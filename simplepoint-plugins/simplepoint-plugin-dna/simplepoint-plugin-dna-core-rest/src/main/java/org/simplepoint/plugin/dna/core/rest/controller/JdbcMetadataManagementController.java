package org.simplepoint.plugin.dna.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.dna.core.api.constants.DnaPaths;
import org.simplepoint.plugin.dna.core.api.service.JdbcMetadataManagementService;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataModels;
import org.simplepoint.plugin.dna.core.api.vo.JdbcMetadataRequests;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JDBC metadata management endpoints.
 */
@RestController
@RequestMapping({DnaPaths.METADATA, DnaPaths.PLATFORM_METADATA})
@Tag(name = "DNA元数据管理", description = "用于浏览数据库树结构、预览数据和执行表结构 DDL")
public class JdbcMetadataManagementController {

  private final JdbcMetadataManagementService service;

  /**
   * Creates the metadata management controller.
   *
   * @param service metadata service
   */
  public JdbcMetadataManagementController(final JdbcMetadataManagementService service) {
    this.service = service;
  }

  /**
   * Returns child metadata nodes.
   *
   * @param dataSourceId datasource id
   * @param request path request
   * @return child nodes
   */
  @PostMapping("/{dataSourceId}/children")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.view')")
  @Operation(summary = "查询元数据子节点", description = "根据当前路径懒加载数据库/catalog/schema/table/view 树节点")
  public Response<?> children(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody(required = false) final JdbcMetadataRequests.PathRequest request
  ) {
    try {
      return BaseController.ok(service.children(dataSourceId, request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Returns table/view structure.
   *
   * @param dataSourceId datasource id
   * @param request path request
   * @return structure result
   */
  @PostMapping("/{dataSourceId}/structure")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.view')")
  @Operation(summary = "查询表结构", description = "查询数据表或视图的字段和约束结构")
  public Response<?> structure(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.PathRequest request
  ) {
    try {
      JdbcMetadataModels.TableStructure structure = service.structure(dataSourceId, request);
      return BaseController.ok(structure);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Returns paged preview data.
   *
   * @param dataSourceId datasource id
   * @param request path request
   * @param pageable pageable
   * @return preview data
   */
  @PostMapping("/{dataSourceId}/preview")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.preview')")
  @Operation(summary = "预览表数据", description = "分页预览数据表或视图中的数据")
  public Response<?> preview(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.PathRequest request,
      final Pageable pageable
  ) {
    try {
      return BaseController.ok(service.preview(dataSourceId, request, pageable));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Creates a namespace.
   *
   * @param dataSourceId datasource id
   * @param request create request
   * @return success
   */
  @PostMapping("/{dataSourceId}/namespaces")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.create-namespace')")
  @Operation(summary = "创建命名空间", description = "创建数据库或 schema")
  public Response<?> createNamespace(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.NamespaceCreateRequest request
  ) {
    try {
      service.createNamespace(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Drops a namespace, table, or view.
   *
   * @param dataSourceId datasource id
   * @param request drop request
   * @return success
   */
  @PostMapping("/{dataSourceId}/drop")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.drop')")
  @Operation(summary = "删除元数据对象", description = "删除数据库、schema、数据表或视图")
  public Response<?> drop(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.DropRequest request
  ) {
    try {
      service.drop(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Creates a table.
   *
   * @param dataSourceId datasource id
   * @param request create request
   * @return success
   */
  @PostMapping("/{dataSourceId}/tables")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.create-table')")
  @Operation(summary = "创建数据表", description = "在指定命名空间下创建数据表")
  public Response<?> createTable(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.TableCreateRequest request
  ) {
    try {
      service.createTable(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Creates a view.
   *
   * @param dataSourceId datasource id
   * @param request create request
   * @return success
   */
  @PostMapping("/{dataSourceId}/views")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.create-view')")
  @Operation(summary = "创建视图", description = "在指定命名空间下创建视图")
  public Response<?> createView(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.ViewCreateRequest request
  ) {
    try {
      service.createView(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Adds a column.
   *
   * @param dataSourceId datasource id
   * @param request add request
   * @return success
   */
  @PostMapping("/{dataSourceId}/columns")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.alter-column')")
  @Operation(summary = "新增字段", description = "向数据表新增字段")
  public Response<?> addColumn(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.ColumnAddRequest request
  ) {
    try {
      service.addColumn(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Alters a column.
   *
   * @param dataSourceId datasource id
   * @param request alter request
   * @return success
   */
  @PostMapping("/{dataSourceId}/columns/alter")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.alter-column')")
  @Operation(summary = "修改字段", description = "修改数据表字段定义")
  public Response<?> alterColumn(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.ColumnAlterRequest request
  ) {
    try {
      service.alterColumn(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Drops a column.
   *
   * @param dataSourceId datasource id
   * @param request drop request
   * @return success
   */
  @PostMapping("/{dataSourceId}/columns/drop")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.drop-column')")
  @Operation(summary = "删除字段", description = "从数据表删除字段")
  public Response<?> dropColumn(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.ColumnDropRequest request
  ) {
    try {
      service.dropColumn(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Adds a constraint.
   *
   * @param dataSourceId datasource id
   * @param request add request
   * @return success
   */
  @PostMapping("/{dataSourceId}/constraints")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.add-constraint')")
  @Operation(summary = "新增约束", description = "向数据表新增主键、唯一、外键或 CHECK 约束")
  public Response<?> addConstraint(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.ConstraintAddRequest request
  ) {
    try {
      service.addConstraint(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Drops a constraint.
   *
   * @param dataSourceId datasource id
   * @param request drop request
   * @return success
   */
  @PostMapping("/{dataSourceId}/constraints/drop")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.metadata.drop-constraint')")
  @Operation(summary = "删除约束", description = "删除数据表上的约束")
  public Response<?> dropConstraint(
      @PathVariable("dataSourceId") final String dataSourceId,
      @RequestBody final JdbcMetadataRequests.ConstraintDropRequest request
  ) {
    try {
      service.dropConstraint(dataSourceId, request);
      return Response.okay();
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  private Response<String> badRequest(final String message) {
    return Response.of(
        ResponseEntity.badRequest()
            .contentType(MediaType.TEXT_PLAIN)
            .body(message)
    );
  }
}
