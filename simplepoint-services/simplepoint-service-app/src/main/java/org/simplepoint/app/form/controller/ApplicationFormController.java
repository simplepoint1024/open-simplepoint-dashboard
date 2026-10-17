package org.simplepoint.app.form.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormDraftView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationInspection;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormPublicationView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormReleaseView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormVersionView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormView;
import org.simplepoint.plugin.app.form.api.model.CreateApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.PrepareApplicationFormDataCommand;
import org.simplepoint.plugin.app.form.api.model.PublishApplicationFormCommand;
import org.simplepoint.plugin.app.form.api.model.SaveApplicationFormDraftCommand;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormPublicationService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin transport for tenant-owned form definitions, drafts and immutable versions. */
@RestController
@RequestMapping("/applications/{applicationId}/forms")
@PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.view')")
@Tag(name = "应用表单", description = "应用内表单定义、草稿和不可变版本")
public class ApplicationFormController {

  private final ApplicationFormService formService;
  private final ApplicationFormPublicationService publicationService;

  /** Creates the form transport adapter. */
  public ApplicationFormController(
      final ApplicationFormService formService,
      final ApplicationFormPublicationService publicationService
  ) {
    this.formService = formService;
    this.publicationService = publicationService;
  }

  /** Lists forms under one application. */
  @GetMapping
  @Operation(summary = "查询应用表单")
  public Response<List<ApplicationFormView>> list(
      @PathVariable("applicationId") final String applicationId
  ) {
    return Response.okay(formService.list(applicationId));
  }

  /** Creates one form with a valid empty main scene. */
  @PostMapping
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.create')")
  @Operation(summary = "创建应用表单")
  public Response<ApplicationFormView> create(
      @PathVariable("applicationId") final String applicationId,
      @RequestBody final CreateFormRequest request
  ) {
    return Response.okay(formService.create(new CreateApplicationFormCommand(
        applicationId,
        request.code(),
        request.name(),
        request.description()
    )));
  }

  /** Reads one form. */
  @GetMapping("/{formId}")
  @Operation(summary = "读取应用表单")
  public Response<ApplicationFormView> get(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId
  ) {
    return Response.okay(formService.get(applicationId, formId));
  }

  /** Reads the current mutable draft. */
  @GetMapping("/{formId}/draft")
  @Operation(summary = "读取表单草稿")
  public Response<ApplicationFormDraftView> draft(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId
  ) {
    return Response.okay(formService.draft(applicationId, formId));
  }

  /** Replaces the current draft with optimistic revision control. */
  @PutMapping("/{formId}/draft")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.edit')")
  @Operation(summary = "保存表单草稿")
  public Response<ApplicationFormDraftView> saveDraft(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @RequestBody final SaveApplicationFormDraftCommand command
  ) {
    return Response.okay(formService.saveDraft(applicationId, formId, command));
  }

  /** Freezes the current draft as an immutable pre-publication version. */
  @PostMapping("/{formId}/versions")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.edit')")
  @Operation(summary = "创建不可变表单版本")
  public Response<ApplicationFormVersionView> createVersion(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @RequestBody final CreateVersionRequest request
  ) {
    return Response.okay(formService.createVersion(
        applicationId,
        formId,
        request.expectedRevision()
    ));
  }

  /** Lists immutable versions newest first. */
  @GetMapping("/{formId}/versions")
  @Operation(summary = "查询表单版本")
  public Response<List<ApplicationFormVersionView>> versions(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId
  ) {
    return Response.okay(formService.versions(applicationId, formId));
  }

  /** Starts or resumes the recoverable DNA data preparation step. */
  @PostMapping("/{formId}/versions/{version}/data-publication")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.publish')")
  @Operation(summary = "准备表单物理数据结构")
  public Response<ApplicationFormPublicationView> prepareData(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @PathVariable("version") final int version,
      @RequestBody final PrepareDataPublicationRequest request
  ) {
    return Response.okay(publicationService.prepareData(
        applicationId,
        formId,
        new PrepareApplicationFormDataCommand(version, request.idempotencyKey())
    ));
  }

  /** Publishes the physical schema and activates one APP runtime page. */
  @PostMapping("/{formId}/versions/{version}/publication")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.publish')")
  @Operation(summary = "发布表单到应用页面")
  public Response<ApplicationFormReleaseView> publish(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @PathVariable("version") final int version,
      @RequestBody final PublishFormRequest request
  ) {
    return Response.okay(publicationService.publish(
        applicationId,
        formId,
        new PublishApplicationFormCommand(version, request.pageId(), request.idempotencyKey())
    ));
  }

  /** Resumes the saved target without accepting a replacement version or idempotency key. */
  @PostMapping("/{formId}/releases/{releaseId}/resume")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.publish')")
  @Operation(summary = "继续已保存的表单发布")
  public Response<ApplicationFormReleaseView> resume(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @PathVariable("releaseId") final String releaseId
  ) {
    return Response.okay(publicationService.resume(applicationId, formId, releaseId));
  }

  /** Resolves cancellation from the persisted release identity under publication authority. */
  @PostMapping("/{formId}/releases/{releaseId}/cancel")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.publish')")
  @Operation(summary = "取消已保存的表单发布")
  public Response<ApplicationFormReleaseView> cancel(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @PathVariable("releaseId") final String releaseId
  ) {
    return Response.okay(publicationService.cancel(applicationId, formId, releaseId));
  }

  /** Reads durable publication progress for polling and recovery. */
  @GetMapping("/{formId}/releases/{releaseId}/inspection")
  @PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.publish')")
  @Operation(summary = "查看表单发布检查")
  public Response<ApplicationFormPublicationInspection> inspection(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @PathVariable("releaseId") final String releaseId
  ) {
    return Response.okay(publicationService.inspection(applicationId, formId, releaseId));
  }

  /** Reads durable publication progress for polling and recovery. */
  @GetMapping("/{formId}/publications/{publicationId}")
  @Operation(summary = "查询表单发布进度")
  public Response<ApplicationFormPublicationView> publication(
      @PathVariable("applicationId") final String applicationId,
      @PathVariable("formId") final String formId,
      @PathVariable("publicationId") final String publicationId
  ) {
    return Response.okay(publicationService.get(applicationId, formId, publicationId));
  }

  /** Form identity supplied by the create dialog; application identity comes from the route. */
  public record CreateFormRequest(String code, String name, String description) {
  }

  /** Optimistic draft revision used when freezing a version. */
  public record CreateVersionRequest(long expectedRevision) {
  }

  /** Browser-generated retry identity for one immutable form version. */
  public record PrepareDataPublicationRequest(String idempotencyKey) {
  }

  /** Target APP page and browser retry identity for a one-click publication. */
  public record PublishFormRequest(String pageId, String idempotencyKey) {
  }
}
