package org.simplepoint.plugin.dna.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.core.api.constants.DnaPaths;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDriverDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDriverDefinitionService;
import org.simplepoint.plugin.dna.core.api.vo.JdbcDriverUploadRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * JDBC driver definition management endpoints.
 */
@RestController
@RequestMapping({DnaPaths.DRIVERS, DnaPaths.PLATFORM_DRIVERS})
@Tag(name = "JDBC驱动管理", description = "用于管理 JDBC 驱动定义、远程驱动下载与本地驱动上传")
public class JdbcDriverDefinitionController
    extends BaseController<JdbcDriverDefinitionService, JdbcDriverDefinition, String> {

  /**
   * Creates a driver definition controller.
   *
   * @param service driver definition service
   */
  public JdbcDriverDefinitionController(final JdbcDriverDefinitionService service) {
    super(service);
  }

  /**
   * Pages driver definitions.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged driver definitions
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.drivers.view')")
  @Operation(summary = "分页查询驱动定义", description = "根据条件分页查询 JDBC 驱动定义")
  public Response<Page<JdbcDriverDefinition>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), JdbcDriverDefinition.class);
  }

  /**
   * Creates a driver definition.
   *
   * @param data driver definition
   * @return created driver definition
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.drivers.create')")
  @Operation(summary = "新增驱动定义", description = "新增一个 JDBC 驱动定义")
  public Response<?> add(@RequestBody final JdbcDriverDefinition data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Creates a driver definition by uploading a local driver jar.
   *
   * @param file uploaded driver jar
   * @param request driver request
   * @return created driver definition
   */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.drivers.upload')")
  @Operation(summary = "上传并新增驱动", description = "通过手动上传 JDBC 驱动 JAR 新增驱动定义")
  public Response<?> uploadCreate(
      @RequestParam("file") final MultipartFile file,
      @ModelAttribute final JdbcDriverUploadRequest request
  ) {
    try {
      return ok(service.createByUpload(file, request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a driver definition.
   *
   * @param data driver definition
   * @return updated driver definition
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.drivers.edit')")
  @Operation(summary = "修改驱动定义", description = "修改一个已存在的 JDBC 驱动定义")
  public Response<?> modify(@RequestBody final JdbcDriverDefinition data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes driver definitions by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.drivers.delete')")
  @Operation(summary = "删除驱动定义", description = "根据 ID 集合删除驱动定义")
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
   * Downloads the configured driver artifact.
   *
   * @param id driver id
   * @return download result
   */
  @PostMapping("/{id}/download")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.drivers.download')")
  @Operation(summary = "下载驱动", description = "根据驱动定义的远程地址下载 JDBC 驱动文件")
  public Response<?> download(@PathVariable("id") final String id) {
    try {
      return ok(service.download(id));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Uploads a local driver jar for an existing driver definition.
   *
   * @param id driver id
   * @param file uploaded driver jar
   * @return updated driver definition
   */
  @PostMapping(value = "/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.drivers.upload')")
  @Operation(summary = "上传驱动包", description = "为已有驱动定义手动上传 JDBC 驱动 JAR 并刷新元数据")
  public Response<?> upload(
      @PathVariable("id") final String id,
      @RequestParam("file") final MultipartFile file
  ) {
    try {
      return ok(service.upload(id, file));
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
