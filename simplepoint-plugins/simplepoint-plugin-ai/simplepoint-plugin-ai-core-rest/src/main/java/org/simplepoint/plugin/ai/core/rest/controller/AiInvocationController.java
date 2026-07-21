package org.simplepoint.plugin.ai.core.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.core.api.constants.AiPaths;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.service.AiInvocationQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, scope-isolated AI invocation usage ledger endpoint. */
@RestController
@RequestMapping({AiPaths.PLATFORM_INVOCATIONS, AiPaths.TENANT_INVOCATIONS})
@Tag(name = "AI调用台账", description = "查询不包含提示词与输出正文的 AI 调用元数据")
public class AiInvocationController
    extends BaseController<AiInvocationQueryService, AiInvocationRecord, String> {

  /** Creates the invocation query controller. */
  public AiInvocationController(final AiInvocationQueryService queryService) {
    super(queryService);
  }

  /** Pages invocation records in the current scope. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAnyAuthority('ai.system.invocations.view', 'ai.invocations.view')"
  )
  @Operation(summary = "分页查询 AI 调用台账")
  public Response<Page<AiInvocationRecord>> limit(
      @RequestParam final Map<String, String> attributes,
      final Pageable pageable
  ) {
    return Response.okay(service.limit(attributes, pageable));
  }
}
