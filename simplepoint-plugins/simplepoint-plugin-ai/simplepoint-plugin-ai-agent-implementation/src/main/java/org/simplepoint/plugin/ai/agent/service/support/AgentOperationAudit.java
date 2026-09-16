package org.simplepoint.plugin.ai.agent.service.support;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Secret-free structured audit records for Agent management operations. */
public final class AgentOperationAudit {

  private static final Logger AUDIT = LoggerFactory.getLogger(
      "org.simplepoint.audit.ai.agent"
  );

  private static final int MAXIMUM_VALUE_LENGTH = 512;

  private AgentOperationAudit() {
  }

  /** Records one successful management operation without request payloads. */
  public static void success(
      final String action,
      final String agentId,
      final String targetId,
      final String detail
  ) {
    AUDIT.info(
        "event=AI_AGENT_OPERATION outcome=SUCCESS action={} agentId={} "
            + "targetId={} actorId={} detail={}",
        safe(action),
        safe(agentId),
        safe(targetId),
        safe(currentActor()),
        safe(detail)
    );
  }

  private static String currentActor() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    return context == null ? null : context.getUserId();
  }

  static String safe(final String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    String normalized = value.replace('\n', '_').replace('\r', '_').trim();
    return normalized.length() <= MAXIMUM_VALUE_LENGTH
        ? normalized
        : normalized.substring(0, MAXIMUM_VALUE_LENGTH);
  }
}
