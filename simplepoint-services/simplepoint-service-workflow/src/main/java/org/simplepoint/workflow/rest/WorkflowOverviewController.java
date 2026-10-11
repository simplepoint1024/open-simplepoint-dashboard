package org.simplepoint.workflow.rest;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.api.model.WorkflowOverview;
import org.simplepoint.plugin.workflow.service.WorkflowOverviewQueries;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Logged-in home bootstrap; each card is independently authorized by the domain query. */
@RestController
public class WorkflowOverviewController {
  private final WorkflowOverviewQueries queries;

  /** Creates the capability-aware dashboard adapter. */
  public WorkflowOverviewController(WorkflowOverviewQueries queries) {
    this.queries = queries;
  }

  /** Returns the authenticated workspace's bounded workflow summary. */
  @GetMapping("/overview")
  @PreAuthorize("isAuthenticated()")
  public Response<WorkflowOverview> overview() {
    return Response.okay(queries.overview());
  }
}
