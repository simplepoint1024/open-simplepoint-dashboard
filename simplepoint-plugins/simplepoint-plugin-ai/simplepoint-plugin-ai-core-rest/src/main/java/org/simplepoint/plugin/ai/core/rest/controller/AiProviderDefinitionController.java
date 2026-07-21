package org.simplepoint.plugin.ai.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.utils.StringUtil;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.service.AiModelCatalogService;
import org.simplepoint.plugin.ai.core.api.service.AiProviderDefinitionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI provider configuration and model discovery endpoints.
 */
@RestController
@RequestMapping({AiPaths.PLATFORM_PROVIDERS, AiPaths.TENANT_PROVIDERS})
@Tag(name = "AI供应商管理", description = "管理 OpenAI、Anthropic 与 OpenAI 兼容模型供应商")
public class AiProviderDefinitionController
    extends BaseController<AiProviderDefinitionService, AiProviderDefinition, String> {

  private final AiModelCatalogService modelCatalogService;

  /**
   * Creates the provider controller.
   *
   * @param service             provider service
   * @param modelCatalogService model catalog service
   */
  public AiProviderDefinitionController(
      final AiProviderDefinitionService service,
      final AiModelCatalogService modelCatalogService
  ) {
    super(service);
    this.modelCatalogService = modelCatalogService;
  }

  /**
   * Pages provider configurations.
   *
   * @param attributes filter attributes
   * @param pageable   paging arguments
   * @return paged providers
  */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.providers.view', 'ai.providers.view')"
  )
  @Operation(summary = "分页查询 AI 供应商")
  public Response<Page<AiProviderDefinition>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), AiProviderDefinition.class);
  }

  /**
   * Creates a provider configuration.
   *
   * @param data provider data
   * @return created provider
  */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.providers.create', 'ai.providers.create')"
  )
  @Operation(summary = "新增 AI 供应商")
  public Response<?> add(@RequestBody final AiProviderDefinition data) {
    return invoke(() -> service.create(data));
  }

  /**
   * Updates a provider configuration.
   *
   * @param data provider data
   * @return updated provider
  */
  @PutMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.providers.edit', 'ai.providers.edit')"
  )
  @Operation(summary = "修改 AI 供应商")
  public Response<?> modify(@RequestBody final AiProviderDefinition data) {
    return invoke(() -> service.modifyById(data));
  }

  /**
   * Deletes provider configurations.
   *
   * @param ids comma-separated ids
   * @return deleted ids
  */
  @DeleteMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.providers.delete', 'ai.providers.delete')"
  )
  @Operation(summary = "删除 AI 供应商")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    try {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return ok(idSet);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex.getMessage());
    }
  }

  /**
   * Tests provider connectivity by requesting the model catalog.
   *
   * @param id provider id
   * @return connection result
  */
  @PostMapping("/{id}/test")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.providers.test', 'ai.providers.test')"
  )
  @Operation(summary = "测试 AI 供应商连接")
  public Response<?> test(@PathVariable("id") final String id) {
    return invoke(() -> modelCatalogService.testConnection(id));
  }

  /**
   * Discovers remote models without persisting them.
   *
   * @param id provider id
   * @return discovered models
  */
  @GetMapping("/{id}/models/discover")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.providers.discover', 'ai.providers.discover')"
  )
  @Operation(summary = "获取供应商可用模型列表")
  public Response<?> discover(@PathVariable("id") final String id) {
    return invoke(() -> modelCatalogService.discoverModels(id));
  }

  /**
   * Synchronizes remote models into the local catalog.
   *
   * @param id provider id
   * @return synchronization result
  */
  @PostMapping("/{id}/models/sync")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.providers.sync', 'ai.providers.sync')"
  )
  @Operation(summary = "同步供应商模型列表")
  public Response<?> sync(@PathVariable("id") final String id) {
    return invoke(() -> modelCatalogService.syncModels(id));
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
