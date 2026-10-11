package org.simplepoint.workflow.rest;

import java.util.List;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.api.model.WorkflowMatter;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowStartOption;
import org.simplepoint.plugin.workflow.service.WorkflowMatterQueries;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Personal matters; domain service additionally enforces each view's distinct permission. */
@RestController
@RequestMapping("/matters")
@PreAuthorize(
    "hasRole('Administrator') or hasAnyAuthority('workflow.instances.started.view',"
        + " 'workflow.instances.view')")
public class WorkflowMatterController {
  private final WorkflowMatterQueries queries;

  /** Creates the matter and published-start-directory HTTP adapter. */
  public WorkflowMatterController(WorkflowMatterQueries queries) {
    this.queries = queries;
  }

  /** Queries immutable published definitions available for starting. */
  @GetMapping("/startable")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.start')")
  public Response<PageResult<WorkflowStartOption>> startable(
      @RequestParam Map<String, String> filters, @PageableDefault(size = 20) Pageable page) {
    return Response.okay(queries.startable(filters, page));
  }

  /** Resolves bounded published definition options for a start form. */
  @GetMapping("/startable/options")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.instances.start')")
  public Response<List<WorkflowStartOption>> startableOptions(
      @RequestParam("ids") List<String> ids) {
    return Response.okay(queries.startableOptions(ids));
  }

  /** Returns the authorized matter view's query schema. */
  @GetMapping("/{view:started|copied|all}/schema")
  public Response<Map<String, Object>> schema(@PathVariable("view") String view) {
    return Response.okay(queries.schema(view));
  }

  /** Queries matters inside the selected view's ownership boundary. */
  @GetMapping("/{view:started|copied|all}")
  public Response<PageResult<WorkflowMatter>> list(
      @PathVariable("view") String view,
      @RequestParam Map<String, String> filters,
      @PageableDefault(size = 20) Pageable pageable) {
    return Response.okay(queries.query(view, filters, pageable));
  }
}
