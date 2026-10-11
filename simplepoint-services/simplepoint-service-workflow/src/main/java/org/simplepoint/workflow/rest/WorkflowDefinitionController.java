package org.simplepoint.workflow.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.CreateDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionPermissions;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.DefinitionSummary;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PageResult;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.PublishDefinitionCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.UpdateDraftCommand;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionDetail;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.VersionSummary;
import org.simplepoint.plugin.workflow.api.service.WorkflowCenterService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped process definition, draft and immutable version endpoints. */
@RestController
@RequestMapping("/definitions")
@PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.definitions.view')")
@Tag(name = "流程定义", description = "流程草稿、版本和发布管理")
public class WorkflowDefinitionController {

  private final WorkflowCenterService service;

  /** Creates the definition endpoint adapter. */
  public WorkflowDefinitionController(final WorkflowCenterService service) {
    this.service = service;
  }

  /** Pages visible definitions. */
  @GetMapping
  @Operation(summary = "分页查询流程定义")
  public Response<PageResult<DefinitionSummary>> definitions(
      @RequestParam(name = "keyword", required = false) final String keyword,
      @RequestParam(name = "page", defaultValue = "0") final int page,
      @RequestParam(name = "size", defaultValue = "20") final int size
  ) {
    return Response.okay(service.definitions(keyword, page, size));
  }

  /** Returns current definition capabilities. */
  @GetMapping("/permissions")
  @Operation(summary = "查询流程定义操作能力")
  public Response<DefinitionPermissions> permissions() {
    return Response.okay(service.definitionPermissions());
  }

  /** Creates a definition draft. */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.definitions.create')")
  @Operation(summary = "创建流程定义和初始草稿")
  public Response<DefinitionDetail> create(@RequestBody final CreateDefinitionCommand command) {
    return Response.okay(service.createDefinition(command));
  }

  /** Reads one definition editor projection. */
  @GetMapping("/{id}")
  @Operation(summary = "读取流程定义、草稿和版本目录")
  public Response<DefinitionDetail> definition(@PathVariable("id") final String id) {
    return Response.okay(service.definition(id));
  }

  /** Saves a draft revision. */
  @PutMapping("/{id}/draft")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.definitions.edit')")
  @Operation(summary = "按乐观修订号保存流程草稿")
  public Response<DefinitionDetail> saveDraft(
      @PathVariable("id") final String id,
      @RequestBody final UpdateDraftCommand command
  ) {
    return Response.okay(service.updateDraft(id, command));
  }

  /** Publishes an immutable version. */
  @PostMapping("/{id}/publish")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.definitions.publish')")
  @Operation(summary = "校验并发布不可变流程版本")
  public Response<VersionSummary> publish(
      @PathVariable("id") final String id,
      @RequestBody final PublishDefinitionCommand command
  ) {
    return Response.okay(service.publish(id, command));
  }

  /** Reads one immutable version. */
  @GetMapping("/{id}/versions/{version}")
  @Operation(summary = "读取指定发布版本")
  public Response<VersionDetail> version(
      @PathVariable("id") final String id,
      @PathVariable("version") final int version
  ) {
    return Response.okay(service.version(id, version));
  }

  /** Copies a version into the draft. */
  @PostMapping("/{id}/versions/{version}/copy-to-draft")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('workflow.definitions.copy-version')")
  @Operation(summary = "复制历史版本为新草稿")
  public Response<DefinitionDetail> copyVersion(
      @PathVariable("id") final String id,
      @PathVariable("version") final int version,
      @RequestParam("expectedRevision") final long expectedRevision
  ) {
    return Response.okay(service.copyVersionToDraft(id, version, expectedRevision));
  }
}
