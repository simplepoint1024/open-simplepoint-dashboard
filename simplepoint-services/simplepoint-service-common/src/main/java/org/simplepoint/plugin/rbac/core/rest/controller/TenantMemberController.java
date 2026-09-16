package org.simplepoint.plugin.rbac.core.rest.controller;

import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.rbac.core.api.pojo.command.TenantMemberCommand;
import org.simplepoint.plugin.rbac.core.api.pojo.vo.TenantMemberView;
import org.simplepoint.plugin.rbac.core.api.service.TenantMemberService;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tenant-members")
@PreAuthorize("isAuthenticated()")
public class TenantMemberController {
  private final TenantMemberService service;
  public TenantMemberController(TenantMemberService service) { this.service = service; }
  @GetMapping("/capabilities")
  public Response<java.util.Map<String, Boolean>> capabilities() { return Response.okay(service.capabilities()); }
  @GetMapping
  public Response<Page<TenantMemberView>> list(Pageable page) { return Response.okay(service.list(page)); }
  @PostMapping
  public Response<Void> add(@RequestBody TenantMemberCommand command) { service.add(command); return Response.okay(null); }
  @PutMapping("/{userId}")
  public Response<Void> update(@PathVariable("userId") String userId, @RequestBody TenantMemberCommand command) {
    service.update(userId, command); return Response.okay(null);
  }
  @DeleteMapping("/{userId}")
  public Response<Void> remove(@PathVariable("userId") String userId) { service.remove(userId); return Response.okay(null); }
}
