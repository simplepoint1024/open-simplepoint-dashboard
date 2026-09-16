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
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.model.AiWorkbenchErrorCode;
import org.simplepoint.plugin.ai.core.api.service.AiApiKeyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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

/** Management endpoints for model gateway API keys. */
@RestController
@RequestMapping(AiPaths.API_KEYS)
@Tag(name = "AI模型API Key", description = "签发、禁用、轮换和吊销对外模型服务凭据")
@Slf4j
public class AiApiKeyController extends BaseController<AiApiKeyService, AiApiKey, String> {

  /** Creates the API key management controller. */
  public AiApiKeyController(final AiApiKeyService service) {
    super(service);
  }

  /** Lists keys within the active management scope. */
  @GetMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('ai.workbench.api-keys.view')")
  @Operation(summary = "分页查询模型 API Key")
  public Response<Page<AiApiKey>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return limit(service.limit(attributes, pageable), AiApiKey.class);
  }

  /** Issues a new key and returns its raw value exactly once. */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('ai.workbench.api-keys.create')")
  @Operation(summary = "签发模型 API Key", description = "完整 Key 仅在本次响应中返回")
  public Response<?> add(@RequestBody final AiApiKey data) {
    return invoke(() -> service.create(data));
  }

  /** Updates editable policy fields without changing the secret. */
  @PutMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('ai.workbench.api-keys.edit')")
  @Operation(summary = "修改模型 API Key 策略")
  public Response<?> modify(@RequestBody final AiApiKey data) {
    return invoke(() -> service.modifyById(data));
  }

  /** Rotates a key and returns its replacement raw value exactly once. */
  @PostMapping("/{id}/rotate")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('ai.workbench.api-keys.rotate')")
  @Operation(summary = "轮换模型 API Key", description = "旧 Key 立即失效，新 Key 仅返回一次")
  public Response<?> rotate(@PathVariable("id") final String id) {
    return invoke(() -> service.rotate(id));
  }

  /** Revokes and soft-deletes selected keys. */
  @DeleteMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('ai.workbench.api-keys.delete')")
  @Operation(summary = "吊销模型 API Key")
  public Response<?> remove(@RequestParam("ids") final String ids) {
    return invoke(() -> {
      Set<String> idSet = StringUtil.stringToSet(ids);
      service.removeByIds(idSet);
      return idSet;
    });
  }

  private Response<?> invoke(final java.util.function.Supplier<?> action) {
    try {
      return ok(action.get());
    } catch (IllegalArgumentException ex) {
      log.warn("AI API key request was rejected", ex);
      return apiKeyError(
          HttpStatus.BAD_REQUEST,
          AiWorkbenchErrorCode.AI_API_KEY_REQUEST_INVALID
      );
    } catch (IllegalStateException ex) {
      log.error("AI API key operation is unavailable", ex);
      return apiKeyError(
          HttpStatus.BAD_REQUEST,
          AiWorkbenchErrorCode.AI_API_KEY_OPERATION_UNAVAILABLE
      );
    } catch (AccessDeniedException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      log.error("Unhandled AI API key operation failure", ex);
      return apiKeyError(
          HttpStatus.INTERNAL_SERVER_ERROR,
          AiWorkbenchErrorCode.AI_API_KEY_OPERATION_FAILED
      );
    }
  }

  private Response<Map<String, String>> apiKeyError(
      final HttpStatus status,
      final AiWorkbenchErrorCode errorCode
  ) {
    return Response.of(ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("errorCode", errorCode.name())));
  }
}
