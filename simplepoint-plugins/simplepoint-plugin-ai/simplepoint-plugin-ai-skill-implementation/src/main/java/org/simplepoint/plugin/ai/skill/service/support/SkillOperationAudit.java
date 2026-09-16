package org.simplepoint.plugin.ai.skill.service.support;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Secret-free structured audit records for Skill management operations. */
public final class SkillOperationAudit {

  private static final Logger AUDIT = LoggerFactory.getLogger(
      "org.simplepoint.audit.ai.skill"
  );

  private static final int MAXIMUM_VALUE_LENGTH = 512;

  private SkillOperationAudit() {
  }

  /** Records one successful management operation without request payloads. */
  public static void success(
      final String action,
      final String skillId,
      final String targetId,
      final String detail
  ) {
    AUDIT.info(
        "event=AI_SKILL_OPERATION outcome=SUCCESS action={} skillId={} "
            + "targetId={} actorId={} detail={}",
        safe(action),
        safe(skillId),
        safe(targetId),
        safe(currentActor()),
        safe(detail)
    );
  }

  private static String currentActor() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return context == null ? null : context.getUserId();
  }

  private static String safe(final String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    String normalized = value.replace('\n', '_').replace('\r', '_').trim();
    return normalized.length() <= MAXIMUM_VALUE_LENGTH
        ? normalized
        : normalized.substring(0, MAXIMUM_VALUE_LENGTH);
  }
}
