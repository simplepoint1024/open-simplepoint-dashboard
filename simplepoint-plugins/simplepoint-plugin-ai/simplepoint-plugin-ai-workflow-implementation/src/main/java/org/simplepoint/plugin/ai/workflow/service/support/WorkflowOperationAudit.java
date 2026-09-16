package org.simplepoint.plugin.ai.workflow.service.support;

import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Secret-free structured audit records for Workflow lifecycle and interventions. */
public final class WorkflowOperationAudit {

  private static final Logger AUDIT = LoggerFactory.getLogger(
      "org.simplepoint.audit.ai.workflow"
  );

  private static final int MAXIMUM_VALUE_LENGTH = 512;

  private WorkflowOperationAudit() {
  }

  /** Records a successful operation without manifest, input, output, or credentials. */
  public static void success(
      final String action,
      final String workflowId,
      final String targetId,
      final String detail
  ) {
    AUDIT.info(
        "event=AI_WORKFLOW_OPERATION outcome=SUCCESS action={} workflowId={} "
            + "targetId={} actorId={} detail={}",
        safe(action),
        safe(workflowId),
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
        ? normalized : normalized.substring(0, MAXIMUM_VALUE_LENGTH);
  }
}
