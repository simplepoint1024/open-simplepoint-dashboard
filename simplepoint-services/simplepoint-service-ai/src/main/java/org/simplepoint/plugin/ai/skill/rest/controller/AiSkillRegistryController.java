package org.simplepoint.plugin.ai.skill.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.Supplier;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.skill.api.constants.AiSkillPaths;
import org.simplepoint.plugin.ai.skill.api.model.SkillRegistryError;
import org.simplepoint.plugin.ai.skill.api.service.SkillManagedRegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Credential-free managed Skill OCI Registry administration endpoints. */
@RestController
@RequestMapping(AiSkillPaths.SKILLS + "/managed-registry")
@Tag(name = "AI Skill Registry", description = "Skill 托管 OCI Registry")
public class AiSkillRegistryController {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiSkillRegistryController.class
  );

  private static final String REGISTRY_UNAVAILABLE =
      "SKILL_MANAGED_REGISTRY_UNAVAILABLE";

  private final SkillManagedRegistryService service;

  /** Creates the managed Registry controller. */
  public AiSkillRegistryController(
      final SkillManagedRegistryService service
  ) {
    this.service = service;
  }

  /** Returns public managed Registry configuration without credentials. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "查询 Skill 托管 Registry 配置")
  public Response<?> describe() {
    return invoke(service::describe);
  }

  /** Performs one bounded server-side Registry connectivity check. */
  @PostMapping("/connectivity-check")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "检查 Skill 托管 Registry 连通性")
  public Response<?> checkConnectivity() {
    return invoke(service::checkConnectivity);
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return Response.okay(operation.get());
    } catch (IllegalArgumentException | IllegalStateException ex) {
      LOG.warn("Managed Skill Registry request rejected with {}",
          REGISTRY_UNAVAILABLE, ex);
      return Response.of(ResponseEntity.badRequest()
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SkillRegistryError(
              REGISTRY_UNAVAILABLE,
              REGISTRY_UNAVAILABLE
          )));
    }
  }
}
