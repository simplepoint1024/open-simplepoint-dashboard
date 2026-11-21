package org.simplepoint.workflow.rest;

import java.util.List;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.api.model.WorkflowInboxItem;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.service.WorkflowInboxQueries;
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

/** Personal workflow inbox, compatible with platform header/advanced query controls. */
@RestController
@RequestMapping("/inbox")
@PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.tasks.view')")
public class WorkflowInboxController {
  private final WorkflowInboxQueries queries;

  /** Creates the personal inbox HTTP adapter. */
  public WorkflowInboxController(WorkflowInboxQueries queries) {
    this.queries = queries;
  }

  /** Returns the shared pending and completed query contract. */
  @GetMapping({"/schema", "/{tab:pending|completed}/schema", "/{tab:pending|completed}/archived/schema"})
  public Response<Map<String, Object>> schema() {
    return Response.okay(queries.schema());
  }

  /** Resolves a task deep link through the personal visibility boundary. */
  @GetMapping("/tasks/{id}")
  public Response<WorkflowInboxItem> task(@PathVariable("id") String id) {
    return Response.okay(queries.task(id));
  }

  /** Queries pending or completed work with bounded database pagination. */
  @GetMapping("/{tab:pending|completed}")
  public Response<PageResult<WorkflowInboxItem>> list(
      @PathVariable("tab") String tab,
      @RequestParam Map<String, String> filters,
      @PageableDefault(size = 20) Pageable pageable) {
    return Response.okay(queries.query(tab, filters, pageable));
  }

  @GetMapping("/{tab:pending|completed}/archived")
  public Response<PageResult<WorkflowInboxItem>> archived(@PathVariable("tab") String tab,
      @RequestParam Map<String, String> filters, Pageable pageable) {
    return Response.okay(queries.query(tab, true, filters, pageable));
  }

  @PutMapping("/{tab:pending|completed}/archived")
  public ResponseEntity<Void> archive(@PathVariable("tab") String tab, @RequestBody List<String> ids) {
    queries.archive(tab, ids, true);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{tab:pending|completed}/archived")
  public ResponseEntity<Void> unarchive(@PathVariable("tab") String tab, @RequestBody List<String> ids) {
    queries.archive(tab, ids, false);
    return ResponseEntity.noContent().build();
  }
}
