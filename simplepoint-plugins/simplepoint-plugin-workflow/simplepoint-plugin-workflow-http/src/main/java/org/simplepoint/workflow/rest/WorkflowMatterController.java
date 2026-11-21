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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal matters use tenant and starter/copied-user predicates. The domain service checks the
 * manager authority separately for the all-instances view.
 */
@RestController
@org.simplepoint.core.permission.DomainDataAuthorization
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
  @GetMapping({"/{view:started|copied|all}/schema", "/{view:started|copied}/archived/schema"})
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

  @GetMapping("/{view:started|copied}/archived")
  public Response<PageResult<WorkflowMatter>> archived(@PathVariable("view") String view,
      @RequestParam Map<String, String> filters, Pageable pageable) {
    return Response.okay(queries.query(view, true, filters, pageable));
  }

  @PutMapping("/{view:started|copied}/archived")
  public ResponseEntity<Void> archive(@PathVariable("view") String view, @RequestBody List<String> ids) {
    queries.archive(view, ids, true);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{view:started|copied}/archived")
  public ResponseEntity<Void> unarchive(@PathVariable("view") String view, @RequestBody List<String> ids) {
    queries.archive(view, ids, false);
    return ResponseEntity.noContent().build();
  }
}
