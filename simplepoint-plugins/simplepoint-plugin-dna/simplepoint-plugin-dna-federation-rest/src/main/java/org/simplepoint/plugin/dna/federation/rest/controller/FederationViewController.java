package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationView;
import org.simplepoint.plugin.dna.federation.api.service.FederationViewService;
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
 * Federation view endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.VIEWS, DnaFederationPaths.PLATFORM_VIEWS})
@Tag(name = "逻辑视图管理", description = "用于管理联邦查询逻辑视图")
public class FederationViewController
    extends BaseController<FederationViewService, FederationView, String> {

  /**
   * Creates a federation view controller.
   *
   * @param service view service
   */
  public FederationViewController(final FederationViewService service) {
    super(service);
  }

  /**
   * Pages federation views.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged views
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.views.view')")
  @Operation(summary = "分页查询逻辑视图", description = "根据条件分页查询逻辑视图定义")
  public Response<Page<FederationView>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), FederationView.class);
  }

  /**
   * Creates a federation view.
   *
   * @param data view definition
   * @return created view
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.views.create')")
  @Operation(summary = "新增逻辑视图", description = "新增一个逻辑视图定义")
  public Response<?> add(@RequestBody final FederationView data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a federation view.
   *
   * @param data view definition
   * @return updated view
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.views.edit')")
  @Operation(summary = "修改逻辑视图", description = "修改一个已存在的逻辑视图定义")
  public Response<?> modify(@RequestBody final FederationView data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes federation views by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.views.delete')")
  @Operation(summary = "删除逻辑视图", description = "根据 ID 集合删除逻辑视图定义")
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
