package org.simplepoint.app.form.controller;

import java.util.List;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceDetail;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferenceOption;
import org.simplepoint.plugin.app.form.api.model.relation.FormReferencePage;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormReferenceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin relation directory transport; scope and configuration are resolved by APP on every read. */
@RestController
@RequestMapping("/runtime/applications/{applicationId}/pages/{pageId}/references/{relationId}")
@PreAuthorize("isAuthenticated()")
public class ApplicationFormReferenceController {
  private final ApplicationFormReferenceService references;

  /** Delegates authorization and reference semantics to the owning domain service. */
  public ApplicationFormReferenceController(ApplicationFormReferenceService references) {
    this.references = references;
  }

  /** Searches candidates using the exact observed source form identity. */
  @GetMapping
  public Response<FormReferencePage> search(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("relationId") String relationId,
      @RequestParam(value = "keyword", defaultValue = "") String keyword,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "50") int size,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion) {
    return Response.okay(
        references.search(
            applicationId,
            pageId,
            relationId,
            keyword,
            page,
            size,
            new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity(
                formId, formVersion)));
  }

  /** Resolves bounded selected identities independently of candidate conditions. */
  @PostMapping("/resolve")
  public Response<List<FormReferenceOption>> resolve(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("relationId") String relationId,
      @RequestBody List<String> recordIds,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion) {
    return Response.okay(
        references.resolve(
            applicationId,
            pageId,
            relationId,
            recordIds,
            new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity(
                formId, formVersion)));
  }

  /** Carries dependent field values in a bounded body instead of exposing them in URLs. */
  @PostMapping("/search")
  public Response<FormReferencePage> searchContext(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("relationId") String relationId,
      @RequestParam(value = "keyword", defaultValue = "") String keyword,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "50") int size,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion,
      @RequestBody
          org.simplepoint.plugin.app.form.api.model.relation.FormReferenceSearchContext context) {
    return Response.okay(
        references.searchContext(
            applicationId,
            pageId,
            relationId,
            keyword,
            page,
            size,
            new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity(
                formId, formVersion),
            context));
  }

  /** Opens a target only through the relation's authorized frozen entry. */
  @GetMapping("/{recordId}")
  public Response<FormReferenceDetail> detail(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("relationId") String relationId,
      @PathVariable("recordId") String recordId,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion) {
    return Response.okay(
        references.detail(
            applicationId,
            pageId,
            relationId,
            recordId,
            new org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity(
                formId, formVersion)));
  }
}
