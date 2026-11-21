package org.simplepoint.app.form.controller;

import java.util.List;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageQuery;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordPageView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRuntimeIdentity;
import org.simplepoint.plugin.app.form.api.model.relation.FormCollectionOperation;
import org.simplepoint.plugin.app.form.api.model.relation.FormCollectionView;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCollectionService;
import org.simplepoint.plugin.app.form.api.service.ApplicationFormCollectionService.Scope;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Browser collection routes use only the active page and a saved record's logical identity. */
@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping(
    "/runtime/applications/{applicationId}/pages/{pageId}/records/{recordId}/collections")
public class ApplicationFormCollectionController {
  private final ApplicationFormCollectionService collections;

  public ApplicationFormCollectionController(ApplicationFormCollectionService collections) {
    this.collections = collections;
  }

  @GetMapping
  public Response<List<FormCollectionView>> views(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("recordId") String recordId,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion) {
    return Response.okay(
        collections.views(
            applicationId,
            pageId,
            new ApplicationFormRuntimeIdentity(formId, formVersion),
            recordId));
  }

  @GetMapping("/{viewId}/schema")
  public Response<Map<String, Object>> schema(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("recordId") String recordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion,
      @RequestParam(name = "inverse", defaultValue = "false") boolean inverse,
      @RequestParam(name = "candidates", defaultValue = "false") boolean candidates) {
    return Response.okay(
        collections.schema(
            applicationId,
            pageId,
            scope(formId, formVersion, recordId, viewId, inverse),
            candidates));
  }

  @PostMapping("/{viewId}/query")
  public Response<ApplicationFormRecordPageView> page(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("recordId") String recordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion,
      @RequestParam(name = "inverse", defaultValue = "false") boolean inverse,
      @RequestParam(name = "candidates", defaultValue = "false") boolean candidates,
      @RequestBody ApplicationFormRecordPageQuery query) {
    return Response.okay(
        collections.page(
            applicationId,
            pageId,
            scope(formId, formVersion, recordId, viewId, inverse),
            candidates,
            query));
  }

  @PostMapping("/{viewId}/observe")
  public Response<FormCollectionOperation.Observation> observe(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("recordId") String recordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion,
      @RequestParam(name = "inverse", defaultValue = "false") boolean inverse,
      @RequestBody FormCollectionOperation.Selection selection) {
    return Response.okay(
        collections.observe(
            applicationId,
            pageId,
            scope(formId, formVersion, recordId, viewId, inverse),
            selection));
  }

  @PostMapping("/{viewId}/mutations")
  public Response<Void> execute(
      @PathVariable("applicationId") String applicationId,
      @PathVariable("pageId") String pageId,
      @PathVariable("recordId") String recordId,
      @PathVariable("viewId") String viewId,
      @RequestParam("formId") String formId,
      @RequestParam("formVersion") int formVersion,
      @RequestParam(name = "inverse", defaultValue = "false") boolean inverse,
      @RequestBody FormCollectionOperation.Command command) {
    collections.execute(
        applicationId, pageId, scope(formId, formVersion, recordId, viewId, inverse), command);
    return Response.okay();
  }

  private static Scope scope(
      String formId, int formVersion, String recordId, String viewId, boolean inverse) {
    return new Scope(
        new ApplicationFormRuntimeIdentity(formId, formVersion), recordId, viewId, inverse);
  }
}
