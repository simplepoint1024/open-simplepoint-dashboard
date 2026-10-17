package org.simplepoint.app.form.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.app.form.service.ApplicationResourceLifecycleService;
import org.simplepoint.plugin.app.form.service.ApplicationResourceLifecycleService.LifecycleView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lifecycle actions use the same server permission boundary as form publication. */
@RestController
@RequestMapping("/applications/{applicationId}/pages/{pageId}/resource-lifecycle")
@ConditionalOnProperty(prefix = "simplepoint.events", name = "enabled", havingValue = "true")
@PreAuthorize("hasRole('Administrator') or hasAuthority('app.workbench.forms.publish')")
public class ApplicationResourceLifecycleController {
  private final ApplicationResourceLifecycleService service;

  /** Creates the thin transport. */
  public ApplicationResourceLifecycleController(ApplicationResourceLifecycleService service) {
    this.service = service;
  }

  /** Inspects blockers and durable completion receipts. */
  @GetMapping
  public Response<LifecycleView> inspect(@PathVariable("applicationId") String applicationId,
                                        @PathVariable("pageId") String pageId) {
    return Response.okay(service.inspect(applicationId, pageId));
  }

  /** Records an idempotent offline intent after checking the inspected generation. */
  @PostMapping("/offline")
  public Response<LifecycleView> offline(@PathVariable("applicationId") String applicationId,
                                        @PathVariable("pageId") String pageId,
                                        @RequestBody OfflineRequest request) {
    return Response.okay(service.offline(applicationId, pageId, request.operationId(), request.expectedGeneration()));
  }

  /** Reissues a pending operation without changing its resource identity or generation. */
  @PostMapping("/retry")
  public Response<LifecycleView> retry(@PathVariable("applicationId") String applicationId,
                                      @PathVariable("pageId") String pageId,
                                      @RequestBody RetryRequest request) {
    return Response.okay(service.retry(applicationId, pageId, request.operationId(), request.requestId()));
  }

  /** Audited business recovery identity; retries keep the original lifecycle operation. */
  public record RetryRequest(String operationId, String requestId) {}

  /** Stable client operation identity enables safe retry after an uncertain HTTP response. */
  public record OfflineRequest(String operationId, long expectedGeneration) {}
}
