package org.simplepoint.workflow.rest;

import java.util.Map;
import java.util.UUID;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.api.WorkflowException;
import org.simplepoint.plugin.workflow.api.model.WorkflowHistoryRemoval.Progress;
import org.simplepoint.plugin.workflow.api.model.WorkflowHistoryRemoval.Request;
import org.simplepoint.plugin.workflow.service.WorkflowHistoryRemovalService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative removal of completed workflows, separate from personal list actions. */
@RestController
@org.simplepoint.core.permission.DomainDataAuthorization
@RequestMapping("/instances/history-removals")
@PreAuthorize("hasRole('Administrator') or (hasAuthority('workflow.instances.view') and hasAuthority('workflow.instances.delete'))")
public class WorkflowHistoryRemovalController {
  private final ObjectProvider<WorkflowHistoryRemovalService> service;

  public WorkflowHistoryRemovalController(ObjectProvider<WorkflowHistoryRemovalService> service) {
    this.service = service;
  }

  @GetMapping("/capability")
  public Response<Map<String, Integer>> capability() {
    return Response.okay(Map.of("maxSelection",
        service.getIfAvailable() == null ? 0 : WorkflowHistoryRemovalService.MAX_BATCH));
  }

  @PostMapping
  public Response<Progress> remove(@RequestBody Request request) {
    return Response.okay(requireService().request(request));
  }

  @GetMapping("/{id}")
  public Response<Progress> progress(@PathVariable("id") UUID id) {
    return Response.okay(requireService().progress(id));
  }

  private WorkflowHistoryRemovalService requireService() {
    var available = service.getIfAvailable();
    if (available == null) {
      throw new WorkflowException(WorkflowException.Code.HISTORY_REMOVAL_BLOCKED,
          "Workflow history removal is unavailable");
    }
    return available;
  }
}
