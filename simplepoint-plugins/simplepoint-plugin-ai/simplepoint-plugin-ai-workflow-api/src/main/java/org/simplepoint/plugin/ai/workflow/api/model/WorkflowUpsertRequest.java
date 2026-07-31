package org.simplepoint.plugin.ai.workflow.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Mutable Workflow definition fields.
 *
 * @param code immutable scope-local code
 * @param name display name
 * @param description optional description
 * @param enabled whether new executions may be submitted
 */
@Schema(title = "AI Workflow Upsert Request")
public record WorkflowUpsertRequest(
    String code,
    String name,
    String description,
    Boolean enabled
) {
}
