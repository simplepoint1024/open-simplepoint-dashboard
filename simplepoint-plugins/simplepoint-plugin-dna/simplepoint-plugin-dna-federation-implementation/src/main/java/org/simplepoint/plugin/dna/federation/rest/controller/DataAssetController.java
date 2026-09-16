package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.DataAsset;
import org.simplepoint.plugin.dna.federation.api.service.DataAssetService;
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
 * Data asset management endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.DATA_ASSETS, DnaFederationPaths.PLATFORM_DATA_ASSETS})
@Tag(name = "数据资产管理", description = "用于管理逻辑数据资产")
public class DataAssetController
    extends BaseController<DataAssetService, DataAsset, String> {

  /**
   * Creates a data asset controller.
   *
   * @param service data asset service
   */
  public DataAssetController(final DataAssetService service) {
    super(service);
  }

  /**
   * Pages data assets.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged data assets
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-assets.view')")
  @Operation(summary = "分页查询数据资产", description = "根据条件分页查询数据资产定义")
  public Response<Page<DataAsset>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), DataAsset.class);
  }

  /**
   * Creates a data asset.
   *
   * @param data asset definition
   * @return created data asset
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-assets.create')")
  @Operation(summary = "新增数据资产", description = "新增一个数据资产定义")
  public Response<?> add(@RequestBody final DataAsset data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a data asset.
   *
   * @param data asset definition
   * @return updated data asset
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-assets.edit')")
  @Operation(summary = "修改数据资产", description = "修改一个已存在的数据资产定义")
  public Response<?> modify(@RequestBody final DataAsset data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes data assets by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-assets.delete')")
  @Operation(summary = "删除数据资产", description = "根据 ID 集合删除数据资产定义")
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
