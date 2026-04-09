package org.simplepoint.plugin.dna.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.core.api.constants.DnaPaths;
import org.simplepoint.plugin.dna.core.api.entity.JdbcDialectSourceDefinition;
import org.simplepoint.plugin.dna.core.api.service.JdbcDialectManagementService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * JDBC dialect management endpoints.
 */
@RestController
@RequestMapping({DnaPaths.DIALECTS, DnaPaths.PLATFORM_DIALECTS})
@Tag(name = "DNA方言管理", description = "用于管理类路径、远程 URL、上传 JAR 三种来源的数据库方言")
public class JdbcDialectManagementController {

  private final JdbcDialectManagementService service;

  /**
   * Creates the dialect management controller.
   *
   * @param service dialect service
   */
  public JdbcDialectManagementController(final JdbcDialectManagementService service) {
    this.service = service;
  }

  /**
   * Lists discovered dialects.
   *
   * @return discovered dialects
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.dialects.view')")
  @Operation(summary = "查询已加载方言", description = "返回当前系统已发现的全部数据库方言以及自动绑定的驱动")
  public Response<?> listLoadedDialects() {
    return BaseController.ok(service.listLoadedDialects());
  }

  /**
   * Lists external dialect sources.
   *
   * @return dialect sources
   */
  @GetMapping("/sources")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.dialects.view')")
  @Operation(summary = "查询方言源", description = "返回外部 URL 与上传 JAR 方言源")
  public Response<?> listSources() {
    return BaseController.ok(service.listSources());
  }

  /**
   * Creates a URL dialect source.
   *
   * @param request source request
   * @return created source
   */
  @PostMapping("/url")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.dialects.create')")
  @Operation(summary = "新增 URL 方言源", description = "通过远程 URL 下载方言 JAR 并注册方言源")
  public Response<?> createUrlSource(@org.springframework.web.bind.annotation.RequestBody final JdbcDialectSourceDefinition request) {
    try {
      return BaseController.ok(service.createUrlSource(request));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Uploads a dialect jar source.
   *
   * @param file dialect jar
   * @param request source request
   * @return created source
   */
  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.dialects.upload')")
  @Operation(summary = "上传方言 JAR", description = "通过上传方言 JAR 注册外部方言源")
  public Response<?> uploadSource(
      @RequestParam("file") final MultipartFile file,
      @ModelAttribute final JdbcDialectSourceDefinition request
  ) {
    try {
      return BaseController.ok(service.uploadSource(request, file));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes external dialect sources.
   *
   * @param ids comma-separated ids
   * @return removed ids
   */
  @DeleteMapping("/sources")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.dialects.delete')")
  @Operation(summary = "删除方言源", description = "删除外部 URL 或上传 JAR 方言源")
  public Response<?> removeSources(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeSources(idSet);
      return BaseController.ok(idSet);
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
