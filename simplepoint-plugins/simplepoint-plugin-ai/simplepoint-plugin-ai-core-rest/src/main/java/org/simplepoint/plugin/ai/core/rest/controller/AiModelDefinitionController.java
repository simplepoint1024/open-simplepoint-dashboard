package org.simplepoint.plugin.ai.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.service.AiModelDefinitionService;
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
 * Local AI model catalog endpoints.
 */
@RestController
@RequestMapping({AiPaths.PLATFORM_MODELS, AiPaths.TENANT_MODELS})
@Tag(name = "AI模型管理", description = "管理自动发现与手工维护的 AI 模型")
public class AiModelDefinitionController
    extends BaseController<AiModelDefinitionService, AiModelDefinition, String> {

  /**
   * Creates the model controller.
   *
   * @param service model service
   */
  public AiModelDefinitionController(final AiModelDefinitionService service) {
    super(service);
  }

  /**
   * Pages model definitions.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged models
  */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.models.view', 'ai.models.view')"
  )
  @Operation(summary = "分页查询 AI 模型")
  public Response<Page<AiModelDefinition>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), AiModelDefinition.class);
  }

  /**
   * Lists enabled models available for invocation in the current scope.
   *
   * @return available models
  */
  @GetMapping("/available")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.models.view', 'ai.models.view')"
  )
  @Operation(summary = "查询当前作用域可调用的 AI 模型")
  public Response<?> available() {
    return ok(service.listAvailableModels());
  }

  /**
   * Creates a model definition.
   *
   * @param data model data
   * @return created model
  */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.models.create', 'ai.models.create')"
  )
  @Operation(summary = "新增 AI 模型")
  public Response<?> add(@RequestBody final AiModelDefinition data) {
    return invoke(() -> service.create(data));
  }

  /**
   * Updates a model definition.
   *
   * @param data model data
   * @return updated model
  */
  @PutMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.models.edit', 'ai.models.edit')"
  )
  @Operation(summary = "修改 AI 模型")
  public Response<?> modify(@RequestBody final AiModelDefinition data) {
    return invoke(() -> service.modifyById(data));
  }

  /**
   * Deletes model definitions.
   *
   * @param ids comma-separated ids
   * @return deleted ids
  */
  @DeleteMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.models.delete', 'ai.models.delete')"
  )
  @Operation(summary = "删除 AI 模型")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return ok(idSet);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  private Response<?> invoke(final java.util.function.Supplier<?> operation) {
    try {
      return ok(operation.get());
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
