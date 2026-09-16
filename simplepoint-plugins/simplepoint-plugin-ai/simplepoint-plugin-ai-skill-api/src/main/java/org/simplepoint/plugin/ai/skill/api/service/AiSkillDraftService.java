package org.simplepoint.plugin.ai.skill.api.service;

import java.util.Optional;
import org.simplepoint.plugin.ai.skill.api.designer.SkillDesignerCompilationResult;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRestoreRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftRevisionView;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftSaveRequest;
import org.simplepoint.plugin.ai.skill.api.model.SkillDraftView;
import org.simplepoint.plugin.ai.skill.api.model.SkillVersionDesignerView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Scope-aware lifecycle for mutable Skill Designer Drafts. */
public interface AiSkillDraftService {

  /** Finds the current Draft of one visible Skill. */
  Optional<SkillDraftView> find(String skillId);

  /** Creates or replaces a Draft with explicit optimistic revision checking. */
  SkillDraftView save(String skillId, SkillDraftSaveRequest request);

  /** Soft deletes the current Draft using explicit optimistic revision checking. */
  void remove(String skillId, long expectedRevision);

  /** Pages immutable historical Draft Revisions. */
  Page<SkillDraftRevisionView> findRevisions(
      String skillId,
      Pageable pageable
  );

  /** Restores one historical snapshot as a new Draft Revision. */
  SkillDraftView restore(
      String skillId,
      long revision,
      SkillDraftRestoreRequest request
  );

  /** Compiles and validates the currently saved Draft. */
  SkillDesignerCompilationResult compile(String skillId);

  /** Converts one immutable version to a read-only Designer projection. */
  SkillVersionDesignerView viewVersion(String skillId, String versionId);

  /** Copies one compatible immutable version into a new mutable Draft Revision. */
  SkillDraftView copyVersion(
      String skillId,
      String versionId,
      SkillDraftRestoreRequest request
  );
}
