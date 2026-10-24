package org.simplepoint.plugin.ai.runtime.rest.controller;

import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.core.http.Response;
import org.simplepoint.core.schema.AnnotatedTableSchemaProvider;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.runtime.api.constants.AiRuntimePaths;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeNode;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimePool;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeSecret;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeWorkload;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Permission-aware table contracts for the four runtime management lists. */
@RestController
public class AiRuntimeTableSchemaController {
  private final AnnotatedTableSchemaProvider schemas;
  private final AiScopeAccessPolicy scopes;

  /** Uses the production schema generator and the same ownership policy as runtime lists. */
  public AiRuntimeTableSchemaController(
      DetailsProviderService details, AiScopeAccessPolicy scopes) {
    this.schemas = new AnnotatedTableSchemaProvider(details);
    this.scopes = scopes;
  }

  /** Platform nodes are visible only to administrators. */
  @GetMapping(AiRuntimePaths.NODES + "/schema")
  @PreAuthorize("hasRole('Administrator')")
  public Response<?> nodes() {
    return schema(AiRuntimeNode.class);
  }

  /** Pool metadata requires the same read or management permission as its list. */
  @GetMapping(AiRuntimePaths.POOLS + "/schema")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage')")
  public Response<?> pools() {
    scopes.currentManagementScope();
    return schema(AiRuntimePool.class);
  }

  /** Secret metadata never exposes plaintext or ciphertext query fields. */
  @GetMapping(AiRuntimePaths.SECRETS + "/schema")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.secrets.manage') "
          + "or hasAuthority('ai.workbench.runtime.pools.manage') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')")
  public Response<?> secrets() {
    scopes.currentManagementScope();
    return schema(AiRuntimeSecret.class);
  }

  /** Workload metadata is scoped in exactly the same way as workload queries. */
  @GetMapping(AiRuntimePaths.WORKLOADS + "/schema")
  @PreAuthorize(
      "hasRole('Administrator') or hasAuthority('ai.workbench.runtime.view') "
          + "or hasAuthority('ai.workbench.runtime.workloads.manage')")
  public Response<?> workloads() {
    scopes.currentManagementScope();
    return schema(AiRuntimeWorkload.class);
  }

  private Response<?> schema(Class<?> model) {
    org.simplepoint.plugin.ai.runtime.service.support.AiRuntimeTableQuery.requireIdentity(model);
    return Response.okay(schemas.schema(model, AuthorizationContextHolder.getContext()));
  }
}
