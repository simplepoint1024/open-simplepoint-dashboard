package org.simplepoint.plugin.rbac.core.rest.controller;

import java.util.*;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.pojo.command.PlatformAccountCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.PlatformAccountView;
import org.simplepoint.plugin.rbac.core.api.service.PlatformAccountService;
import org.simplepoint.security.entity.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/accounts")
@PreAuthorize("isAuthenticated()")
public class PlatformAccountController {
  private final PlatformAccountService service;
  public PlatformAccountController(PlatformAccountService service) { this.service = service; }

  @GetMapping("/capabilities")
  public Response<Map<String, Object>> capabilities() { return Response.okay(service.capabilities()); }

  @GetMapping("/schema")
  public Response<Map<String, Object>> accountSchema() { return Response.okay(service.accountSchema()); }

  @GetMapping("/audit/schema")
  public Response<Map<String, Object>> auditSchema() { return Response.okay(service.auditSchema()); }

  @GetMapping
  public Response<Page<PlatformAccountView>> list(@RequestParam Map<String, String> attributes, Pageable page) {
    return Response.okay(service.list(attributes, page));
  }
  @PostMapping
  public Response<PlatformAccountView> create(@RequestBody PlatformAccountCommand command) {
    return Response.okay(service.create(command));
  }
  @PutMapping("/{userId}")
  public Response<PlatformAccountView> update(@PathVariable("userId") String userId, @RequestBody PlatformAccountCommand command) {
    return Response.okay(service.update(userId, command));
  }
  @PutMapping("/{userId}/identity")
  public Response<PlatformAccountView> identity(@PathVariable("userId") String userId, @RequestBody PlatformAccountCommand command) {
    return Response.okay(service.assignIdentity(userId, command));
  }
  @GetMapping("/audit")
  public Response<Page<PlatformSecurityAudit>> audit(@RequestParam Map<String, String> attributes, Pageable page) {
    return Response.okay(service.audit(attributes, page));
  }
}
