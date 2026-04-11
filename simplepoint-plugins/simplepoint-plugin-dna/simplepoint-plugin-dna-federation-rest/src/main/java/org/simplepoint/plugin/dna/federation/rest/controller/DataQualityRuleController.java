package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.DataQualityRule;
import org.simplepoint.plugin.dna.federation.api.service.DataQualityRuleService;
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
 * Data quality rule management and execution endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.DATA_QUALITY, DnaFederationPaths.PLATFORM_DATA_QUALITY})
@Tag(name = "数据质量管理", description = "用于管理和执行数据质量检查规则")
public class DataQualityRuleController
    extends BaseController<DataQualityRuleService, DataQualityRule, String> {

  /**
   * Creates a data quality rule controller.
   *
   * @param service data quality rule service
   */
  public DataQualityRuleController(final DataQualityRuleService service) {
    super(service);
  }

  /**
   * Pages data quality rules.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged quality rules
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-quality.view')")
  @Operation(summary = "分页查询质量规则", description = "根据条件分页查询数据质量规则")
  public Response<Page<DataQualityRule>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), DataQualityRule.class);
  }

  /**
   * Creates a quality rule.
   *
   * @param data rule definition
   * @return created rule
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-quality.create')")
  @Operation(summary = "新增质量规则", description = "新增一条数据质量检查规则")
  public Response<?> add(@RequestBody final DataQualityRule data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a quality rule.
   *
   * @param data rule definition
   * @return updated rule
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-quality.edit')")
  @Operation(summary = "修改质量规则", description = "修改一条已存在的数据质量检查规则")
  public Response<?> modify(@RequestBody final DataQualityRule data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes quality rules by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-quality.delete')")
  @Operation(summary = "删除质量规则", description = "根据 ID 集合删除数据质量规则")
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
   * Executes a quality check on a specific rule.
   *
   * @param id rule id
   * @return rule with updated execution result
   */
  @PostMapping("/execute")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.data-quality.execute')")
  @Operation(summary = "执行质量检查", description = "立即执行指定规则的质量检查")
  public Response<?> execute(@RequestParam("id") final String id) {
    try {
      return ok(service.executeCheck(id));
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
