package org.simplepoint.app.catalog.controller;

import java.util.Map;
import org.simplepoint.app.catalog.service.ApplicationNavigationService;
import org.simplepoint.app.catalog.service.ApplicationNavigationState;
import org.simplepoint.core.http.Response;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/applications/{id}/navigation")
@PreAuthorize(
    "hasRole('Administrator') or (hasAuthority('app.workbench.view') and"
        + " hasAuthority('app.workbench.forms.edit'))")
public class ApplicationNavigationController {
  private final ApplicationNavigationService navigation;

  public ApplicationNavigationController(ApplicationNavigationService navigation) {
    this.navigation = navigation;
  }

  @GetMapping
  public Response<ApplicationNavigationState.View> view(@PathVariable("id") String id) {
    return Response.okay(navigation.view(id));
  }

  @PostMapping
  public Response<ApplicationNavigationState.View> update(
      @PathVariable("id") String id, @RequestBody ApplicationNavigationState.Update command) {
    return Response.okay(navigation.update(id, command));
  }

  @PostMapping("/cancel")
  public Response<ApplicationNavigationState.View> cancel(
      @PathVariable("id") String id, @RequestBody ApplicationNavigationState.Update command) {
    return Response.okay(navigation.cancel(id, command));
  }

  @GetMapping("/directories")
  public Response<Map<String, Object>> directories(
      @PathVariable("id") String id,
      @RequestParam(name = "keyword", defaultValue = "") String keyword,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    if (page < 0 || page > 1000 || size < 1 || size > 100)
      throw new org.simplepoint.plugin.app.form.api.error.ApplicationFormException(
          org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode.DOCUMENT_INVALID,
          "Invalid directory page");
    var rows = navigation.directories(id, keyword, page * size, size);
    return Response.okay(
        Map.of(
            "content",
            rows.content(),
            "number",
            page,
            "size",
            size,
            "totalElements",
            rows.totalElements()));
  }
}
