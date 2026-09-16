package org.simplepoint.plugin.ai.skill.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.function.Supplier;
import org.simplepoint.core.http.Response;
import org.simplepoint.plugin.ai.skill.api.constants.AiSkillPaths;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftError;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRestoreRequest;
import org.simplepoint.plugin.ai.skill.api.service.AiSkillDraftService;
import org.simplepoint.plugin.ai.skill.api.service.SkillDraftConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only visualization and safe Draft copying for immutable Skill versions. */
@RestController
@RequestMapping(AiSkillPaths.SKILLS + "/{skillId}/versions/{versionId}/designer")
@Tag(name = "AI Skill Version Designer", description = "不可变 Skill 版本可视化与草稿复制")
public class AiSkillVersionDesignerController {

  private static final Logger LOG = LoggerFactory.getLogger(
      AiSkillVersionDesignerController.class
  );

  private static final String REVISION_CONFLICT =
      "SKILL_DRAFT_REVISION_CONFLICT";

  private static final String REQUEST_INVALID =
      "SKILL_VERSION_DESIGNER_REQUEST_INVALID";

  private final AiSkillDraftService service;

  /** Creates the immutable version Designer controller. */
  public AiSkillVersionDesignerController(final AiSkillDraftService service) {
    this.service = service;
  }

  /** Returns a read-only Designer projection without mutating the version. */
  @GetMapping
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.view') "
          + "or hasAuthority('ai.workbench.skills.drafts.view')"
  )
  @Operation(summary = "查询 Skill 不可变版本设计图")
  public Response<?> find(
      @PathVariable("skillId") final String skillId,
      @PathVariable("versionId") final String versionId
  ) {
    return invoke(() -> service.viewVersion(skillId, versionId));
  }

  /** Copies a compatible immutable version into a new Draft Revision. */
  @PostMapping("/copy-to-draft")
  @PreAuthorize(
      "hasRole('Administrator') "
          + "or hasAuthority('ai.workbench.skills.drafts.manage')"
  )
  @Operation(summary = "基于 Skill 不可变版本创建草稿修订")
  public Response<?> copyToDraft(
      @PathVariable("skillId") final String skillId,
      @PathVariable("versionId") final String versionId,
      @RequestBody final SkillDraftRestoreRequest request
  ) {
    return invoke(() -> service.copyVersion(skillId, versionId, request));
  }

  private Response<?> invoke(final Supplier<?> operation) {
    try {
      return Response.okay(operation.get());
    } catch (SkillDraftConflictException exception) {
      LOG.warn("Skill Version Designer request rejected with {}",
          REVISION_CONFLICT, exception);
      return Response.of(ResponseEntity.status(HttpStatus.CONFLICT)
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SkillDraftError(
              REVISION_CONFLICT,
              REVISION_CONFLICT,
              exception.getExpectedRevision(),
              exception.getCurrentRevision()
          )));
    } catch (IllegalArgumentException | IllegalStateException exception) {
      LOG.warn("Skill Version Designer request rejected with {}",
          REQUEST_INVALID, exception);
      return Response.of(ResponseEntity.badRequest()
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SkillDraftError(
              REQUEST_INVALID,
              REQUEST_INVALID,
              null,
              null
          )));
    }
  }
}
