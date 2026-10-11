package org.simplepoint.workflow.rest;

import io.swagger.v3.oas.annotations.Operation;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.service.WorkflowNumericLiteralPreview;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stateless previews never read or change a definition, directory or running process. */
@RestController
@RequestMapping("/definitions/literal-preview")
@PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.definitions.view')")
public class WorkflowLiteralPreviewController {

  private final WorkflowNumericLiteralPreview service;

  /** Creates the narrowly scoped engine preview adapter. */
  public WorkflowLiteralPreviewController(final WorkflowNumericLiteralPreview service) {
    this.service = service;
  }

  /** Resolves only a numeric literal to its engine identity text. */
  @GetMapping
  @Operation(summary = "预览数字常量表达式的实际身份值")
  public Response<LiteralPreview> preview(@RequestParam("expression") final String expression) {
    return Response.okay(new LiteralPreview(service.preview(expression)));
  }

  /** A JSON object preserves numeric-looking identity text through HTTP serializers. */
  public record LiteralPreview(String value) {
  }
}
