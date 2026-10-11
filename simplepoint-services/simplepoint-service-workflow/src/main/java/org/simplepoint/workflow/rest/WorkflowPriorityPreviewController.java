package org.simplepoint.workflow.rest;

import io.swagger.v3.oas.annotations.Operation;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.service.WorkflowPriorityLiteralPreview;
import org.simplepoint.plugin.workflow.service.WorkflowPriorityLiteralPreview.PriorityPreview;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only, scalar-only task-priority previews. No definition or directory is queried or changed. */
@RestController
@RequestMapping("/definitions/priority-preview")
@PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.definitions.view')")
public class WorkflowPriorityPreviewController {

  private final WorkflowPriorityLiteralPreview service;

  /** Creates the native priority adapter. */
  public WorkflowPriorityPreviewController(final WorkflowPriorityLiteralPreview service) {
    this.service = service;
  }

  /** Distinguishes numeric truncation from strict string integer parsing. */
  @GetMapping
  @Operation(summary = "预览优先级常量的实际引擎值")
  public Response<PriorityPreview> preview(@RequestParam("value") final String value) {
    return Response.okay(service.preview(value));
  }
}
