package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryPolicy;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryPolicyService;
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
 * Federation query policy endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.QUERY_POLICIES, DnaFederationPaths.PLATFORM_QUERY_POLICIES})
@Tag(name = "查询策略管理", description = "用于管理联邦查询策略")
public class FederationQueryPolicyController
    extends BaseController<FederationQueryPolicyService, FederationQueryPolicy, String> {

  /**
   * Creates a federation query policy controller.
   *
   * @param service query policy service
   */
  public FederationQueryPolicyController(final FederationQueryPolicyService service) {
    super(service);
  }

  /**
   * Pages federation query policies.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged query policies
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-policies.view')")
  @Operation(summary = "分页查询查询策略", description = "根据条件分页查询联邦查询策略定义")
  public Response<Page<FederationQueryPolicy>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), FederationQueryPolicy.class);
  }

  /**
   * Creates a federation query policy.
   *
   * @param data query policy definition
   * @return created query policy
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-policies.create')")
  @Operation(summary = "新增查询策略", description = "新增一个联邦查询策略定义")
  public Response<?> add(@RequestBody final FederationQueryPolicy data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a federation query policy.
   *
   * @param data query policy definition
   * @return updated query policy
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-policies.edit')")
  @Operation(summary = "修改查询策略", description = "修改一个已存在的联邦查询策略定义")
  public Response<?> modify(@RequestBody final FederationQueryPolicy data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes federation query policies by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.query-policies.delete')")
  @Operation(summary = "删除查询策略", description = "根据 ID 集合删除联邦查询策略定义")
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
