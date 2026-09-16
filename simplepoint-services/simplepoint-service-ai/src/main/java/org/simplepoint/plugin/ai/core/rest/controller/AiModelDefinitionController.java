package org.simplepoint.plugin.ai.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiWorkbenchErrorCode;
import org.simplepoint.plugin.ai.core.api.service.AiModelDefinitionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
@RequestMapping(AiPaths.MODELS)
@Tag(name = "AI模型管理", description = "管理自动发现与手工维护的 AI 模型")
@Slf4j
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
      "hasRole('Administrator') or hasAuthority('ai.workbench.models.view')"
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
      "hasRole('Administrator') or hasAuthority('ai.workbench.models.view')"
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
      "hasRole('Administrator') or hasAuthority('ai.workbench.models.create')"
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
      "hasRole('Administrator') or hasAuthority('ai.workbench.models.edit')"
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
      "hasRole('Administrator') or hasAuthority('ai.workbench.models.delete')"
  )
  @Operation(summary = "删除 AI 模型")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    return invoke(() -> {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return idSet;
    });
  }

  private Response<?> invoke(final java.util.function.Supplier<?> operation) {
    try {
      return ok(operation.get());
    } catch (IllegalArgumentException ex) {
      log.warn("AI model request was rejected", ex);
      return modelError(
          HttpStatus.BAD_REQUEST,
          AiWorkbenchErrorCode.AI_MODEL_REQUEST_INVALID
      );
    } catch (IllegalStateException ex) {
      log.error("AI model operation is unavailable", ex);
      return modelError(
          HttpStatus.BAD_REQUEST,
          AiWorkbenchErrorCode.AI_MODEL_OPERATION_UNAVAILABLE
      );
    } catch (AccessDeniedException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      log.error("Unhandled AI model operation failure", ex);
      return modelError(
          HttpStatus.INTERNAL_SERVER_ERROR,
          AiWorkbenchErrorCode.AI_MODEL_OPERATION_FAILED
      );
    }
  }

  private Response<Map<String, String>> modelError(
      final HttpStatus status,
      final AiWorkbenchErrorCode errorCode
  ) {
    return Response.of(
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("errorCode", errorCode.name()))
    );
  }
}
