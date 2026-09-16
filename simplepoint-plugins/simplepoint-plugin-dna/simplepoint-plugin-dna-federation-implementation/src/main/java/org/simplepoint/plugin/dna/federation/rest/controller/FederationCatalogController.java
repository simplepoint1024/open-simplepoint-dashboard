package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationCatalog;
import org.simplepoint.plugin.dna.federation.api.service.FederationCatalogService;
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
 * Federation catalog management endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.CATALOGS, DnaFederationPaths.PLATFORM_CATALOGS})
@Tag(name = "数据目录管理", description = "用于管理 DNA 数据目录定义")
public class FederationCatalogController
    extends BaseController<FederationCatalogService, FederationCatalog, String> {

  /**
   * Creates a federation catalog controller.
   *
   * @param service catalog service
   */
  public FederationCatalogController(final FederationCatalogService service) {
    super(service);
  }

  /**
   * Pages federation catalogs.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged catalogs
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.catalogs.view')")
  @Operation(summary = "分页查询数据目录", description = "根据条件分页查询数据目录定义")
  public Response<Page<FederationCatalog>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), FederationCatalog.class);
  }

  /**
   * Creates a federation catalog.
   *
   * @param data catalog definition
   * @return created catalog
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.catalogs.create')")
  @Operation(summary = "新增数据目录", description = "新增一个数据目录定义")
  public Response<?> add(@RequestBody final FederationCatalog data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a federation catalog.
   *
   * @param data catalog definition
   * @return updated catalog
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.catalogs.edit')")
  @Operation(summary = "修改数据目录", description = "修改一个已存在的数据目录定义")
  public Response<?> modify(@RequestBody final FederationCatalog data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes federation catalogs by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.catalogs.delete')")
  @Operation(summary = "删除数据目录", description = "根据 ID 集合删除数据目录定义")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return ok(idSet);
    } catch (IllegalArgumentException ex) {
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
