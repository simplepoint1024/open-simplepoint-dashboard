package org.simplepoint.plugin.dna.federation.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.dna.federation.api.constants.DnaFederationPaths;
import org.simplepoint.plugin.dna.federation.api.entity.FederationSchema;
import org.simplepoint.plugin.dna.federation.api.service.FederationSchemaService;
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
 * Federation schema endpoints.
 */
@RestController
@RequestMapping({DnaFederationPaths.SCHEMAS, DnaFederationPaths.PLATFORM_SCHEMAS})
@Tag(name = "逻辑 Schema 管理", description = "用于管理联邦目录下的逻辑 Schema")
public class FederationSchemaController
    extends BaseController<FederationSchemaService, FederationSchema, String> {

  /**
   * Creates a federation schema controller.
   *
   * @param service schema service
   */
  public FederationSchemaController(final FederationSchemaService service) {
    super(service);
  }

  /**
   * Pages federation schemas.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged schemas
   */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.schemas.view')")
  @Operation(summary = "分页查询逻辑 Schema", description = "根据条件分页查询逻辑 Schema 定义")
  public Response<Page<FederationSchema>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), FederationSchema.class);
  }

  /**
   * Creates a federation schema.
   *
   * @param data schema definition
   * @return created schema
   */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.schemas.create')")
  @Operation(summary = "新增逻辑 Schema", description = "新增一个逻辑 Schema 定义")
  public Response<?> add(@RequestBody final FederationSchema data) {
    try {
      return ok(service.create(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Updates a federation schema.
   *
   * @param data schema definition
   * @return updated schema
   */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.schemas.edit')")
  @Operation(summary = "修改逻辑 Schema", description = "修改一个已存在的逻辑 Schema 定义")
  public Response<?> modify(@RequestBody final FederationSchema data) {
    try {
      return ok(service.modifyById(data));
    } catch (IllegalArgumentException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Deletes federation schemas by ids.
   *
   * @param ids comma-separated ids
   * @return deleted ids
   */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('dna.federation.schemas.delete')")
  @Operation(summary = "删除逻辑 Schema", description = "根据 ID 集合删除逻辑 Schema 定义")
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
