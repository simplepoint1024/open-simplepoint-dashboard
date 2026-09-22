package org.simplepoint.plugin.rbac.core.rest.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.jackson.FieldPermissionPolicy;
import org.simplepoint.plugin.rbac.core.api.pojo.command.PlatformAccountCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PlatformAccountView;
import org.simplepoint.plugin.rbac.core.api.service.PlatformAccountService;
import org.simplepoint.security.entity.PlatformSecurityAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform account reads and separately authorized account/identity mutations. */
@RestController
@RequestMapping("/platform/accounts")
@PreAuthorize("isAuthenticated()")
public class PlatformAccountController {
  private final PlatformAccountService service;

  /** Uses the service that rechecks persisted platform identity on every operation. */
  public PlatformAccountController(PlatformAccountService service) {
    this.service = service;
  }

  /** Lists current platform actions and step-up requirements. */
  @GetMapping("/capabilities")
  public Response<Map<String, Object>> capabilities() {
    return Response.okay(service.capabilities());
  }

  /** Returns the account list schema and enforceable query capabilities. */
  @GetMapping("/schema")
  public Response<Map<String, Object>> accountSchema() {
    return Response.okay(service.accountSchema());
  }

  /** Returns the audit list schema and enforceable query capabilities. */
  @GetMapping("/audit/schema")
  public Response<Map<String, Object>> auditSchema() {
    return Response.okay(service.auditSchema());
  }

  /** Projects a bounded SQL page through the same field policy as the query schema. */
  @GetMapping
  public Response<Page<Map<String, Object>>> list(
      @RequestParam Map<String, String> attributes, Pageable page) {
    return Response.okay(service.list(attributes, page).map(PlatformAccountView::project));
  }

  /** Creates an account without assigning platform identity. */
  @PostMapping
  public Response<PlatformAccountView> create(@RequestBody PlatformAccountCommand command) {
    return Response.okay(service.create(command));
  }

  /** Updates account attributes after step-up and optimistic revision validation. */
  @PutMapping("/{userId}")
  public Response<PlatformAccountView> update(
      @PathVariable("userId") String userId, @RequestBody PlatformAccountCommand command) {
    return Response.okay(service.update(userId, command));
  }

  /** Changes platform roles only through the independent identity control plane. */
  @PutMapping("/{userId}/identity")
  public Response<PlatformAccountView> identity(
      @PathVariable("userId") String userId, @RequestBody PlatformAccountCommand command) {
    return Response.okay(service.assignIdentity(userId, command));
  }

  /** Returns filtered audit records without bypassing field policies via raw serialization. */
  @GetMapping("/audit")
  public Response<Page<Map<String, Object>>> audit(
      @RequestParam Map<String, String> attributes, Pageable page) {
    return Response.okay(
        service
            .audit(attributes, page)
            .map(
                entry -> {
                  Map<String, Object> fields = new LinkedHashMap<>();
                  fields.put("id", entry.getId());
                  fields.put("actorId", entry.getActorId());
                  fields.put("targetId", entry.getTargetId());
                  fields.put("action", entry.getAction());
                  fields.put("beforeState", entry.getBeforeState());
                  fields.put("afterState", entry.getAfterState());
                  fields.put("reason", entry.getReason());
                  fields.put("occurredAt", entry.getOccurredAt());
                  return FieldPermissionPolicy.project(
                      PlatformSecurityAudit.class, fields);
                }));
  }
}
