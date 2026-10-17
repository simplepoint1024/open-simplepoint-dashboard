package org.simplepoint.app.form.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeView;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormRuntimeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin runtime transport that resolves pages through server-owned active bindings. */
@RestController
@RequestMapping("/runtime/applications/{applicationId}/pages")
@PreAuthorize("isAuthenticated()")
@Tag(name = "应用表单运行时", description = "已发布应用页面的不可变表单协议")
public class ApplicationFormRuntimeController {

  private final ApplicationFormRuntimeService runtime;

  /** Creates the runtime transport. */
  public ApplicationFormRuntimeController(final ApplicationFormRuntimeService runtime) {
    this.runtime = runtime;
  }

  /** Reads the active immutable form version after dynamic page authorization. */
  @GetMapping("/{pageId}")
  @Operation(summary = "读取已发布应用页面")
  public Response<ApplicationFormRuntimeView> page(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("pageId") final String pageId
  ) {
    return Response.okay(runtime.page(applicationId, pageId));
  }
}
