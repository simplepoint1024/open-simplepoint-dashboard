package org.simplepoint.app.form.controller;

import java.util.List;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.service.ApplicationFormRemoval;
import org.simplepoint.plugin.app.form.service.ApplicationFormRemoval.Progress;
import org.simplepoint.plugin.app.form.service.ApplicationFormRemoval.Selection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@org.simplepoint.core.permission.DomainDataAuthorization
@RequestMapping("/applications/{applicationId}/forms/removals")
@PreAuthorize("hasRole('Administrator') or (hasAuthority('app.workbench.view') and hasAuthority('app.workbench.forms.delete'))")
public class ApplicationFormRemovalController {
  private final ObjectProvider<ApplicationFormRemoval> service;

  public ApplicationFormRemovalController(ObjectProvider<ApplicationFormRemoval> service) {
    this.service = service;
  }

  @GetMapping("/capability")
  public Response<Map<String, Boolean>> capability(@PathVariable("applicationId") String applicationId) {
    return Response.okay(Map.of("enabled", service.getIfAvailable() != null));
  }

  @PostMapping
  public Response<List<Progress>> remove(@PathVariable("applicationId") String applicationId,
      @RequestBody List<Selection> selections) {
    return Response.okay(requireService().requestBatch(applicationId, selections));
  }

  @GetMapping
  public Response<List<Progress>> progress(@PathVariable("applicationId") String applicationId,
      @RequestParam("formIds") List<String> formIds) {
    return Response.okay(requireService().progressBatch(applicationId, formIds));
  }

  private ApplicationFormRemoval requireService() {
    var available = service.getIfAvailable();
    if (available == null) throw new ApplicationFormException(ApplicationFormErrorCode.FORM_DELETE_BLOCKED,
        "Published form removal is unavailable");
    return available;
  }
}
