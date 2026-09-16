package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryTemplate;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryTemplateService;
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
 * Federation query template endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.QUERY_TEMPLATES, DnaFederationPaths.PLATFORM_QUERY_TEMPLATES})
@Tag(name = "查询模板管理", description = "用于管理可复用的 SQL 查询模板")
public class FederationQueryTemplateController
    extends BaseController<FederationQueryTemplateService, FederationQueryTemplate, String> {

  /**
   * Creates a federation query template controller.
   *
   * @param service query template service
   */
  public FederationQueryTemplateController(final FederationQueryTemplateService service) {
    super(service);
  }

  /**
   * Pages federation query templates.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged query templates
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-templates.view')")
  @Operation(summary = "分页查询查询模板", description = "根据条件分页查询联邦查询模板定义")
  public Response<Page<FederationQueryTemplate>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), FederationQueryTemplate.class);
  }

  /**
   * Returns all active public query templates for the SQL console template picker.
   *
   * @return public templates
   */
  @GetMapping("/public")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-templates.view')")
  @Operation(summary = "查询公共模板", description = "返回所有已启用的公共查询模板")
  public Response<?> listPublic() {
    return ok(service.findAllActivePublic());
  }

  /**
   * Creates a federation query template.
   *
   * @param data query template definition
   * @return created query template
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-templates.create')")
  @Operation(summary = "新增查询模板", description = "新增一个联邦查询模板定义")
  public Response<?> add(@RequestBody final FederationQueryTemplate data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a federation query template.
   *
   * @param data query template definition
   * @return updated query template
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-templates.edit')")
  @Operation(summary = "修改查询模板", description = "修改一个已存在的联邦查询模板定义")
  public Response<?> modify(@RequestBody final FederationQueryTemplate data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes federation query templates by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-templates.delete')")
  @Operation(summary = "删除查询模板", description = "根据 ID 集合删除联邦查询模板定义")
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
