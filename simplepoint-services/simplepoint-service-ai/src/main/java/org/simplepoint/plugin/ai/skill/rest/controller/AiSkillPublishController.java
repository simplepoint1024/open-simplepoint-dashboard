package org.simplepoint.plugin.ai.skill.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.Supplier;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.skill.api.constants.AiSkillPaths;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftPublishRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillRegistryError;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillPublishService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Durable one-click publication endpoints for Skill Designer Drafts. */
@RestController
@RequestMapping(AiSkillPaths.SKILLS + "/{skillId}/draft/publish-tasks")
@Tag(name = "AI Skill Publish", description = "Skill 草稿一键发布")
public class AiSkillPublishController {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiSkillPublishController.class
  );

  private static final String REQUEST_INVALID =
      "SKILL_PUBLISH_REQUEST_INVALID";

  private final AiSkillPublishService service;

  /** Creates the publication controller. */
  public AiSkillPublishController(final AiSkillPublishService service) {
    this.service = service;
  }

  /** Starts or returns one idempotent managed publication. */
  @PostMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "启动 Skill 草稿一键发布")
  public Response<?> start(
      @PathVariable("skillId") final String skillId,
      @RequestBody final SkillDraftPublishRequest request
  ) {
    return invoke(() -> service.start(skillId, request));
  }

  /** Pages durable publications for one Skill. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "分页查询 Skill 发布任务")
  public Response<?> findAll(
      @PathVariable("skillId") final String skillId,
      final Pageable pageable
  ) {
    return invoke(() -> service.findAll(skillId, pageable));
  }

  /** Returns one durable publication task. */
  @GetMapping("/{taskId}")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "查询 Skill 发布任务")
  public Response<?> find(
      @PathVariable("skillId") final String skillId,
      @PathVariable("taskId") final String taskId
  ) {
    try {
      return service.find(skillId, taskId)
          .<Response<?>>map(Response::okay)
          .orElseGet(Response::nf);
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex);
    }
  }

  /** Requeues one terminal failed publication after an operator fix. */
  @PostMapping("/{taskId}/retry")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.publish')"
  )
  @Operation(summary = "重试失败的 Skill 发布任务")
  public Response<?> retry(
      @PathVariable("skillId") final String skillId,
      @PathVariable("taskId") final String taskId
  ) {
    return invoke(() -> service.retry(skillId, taskId));
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return Response.okay(operation.get());
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return badRequest(ex);
    }
  }

  private Response<?> badRequest(final RuntimeException exception) {
    LOG.warn("Skill publication request rejected with {}",
        REQUEST_INVALID, exception);
    return Response.of(ResponseEntity.badRequest()
        .contentType(MediaType.APPLICATION_JSON)
        .body(new SkillRegistryError(
            REQUEST_INVALID,
            REQUEST_INVALID
        )));
  }
}
