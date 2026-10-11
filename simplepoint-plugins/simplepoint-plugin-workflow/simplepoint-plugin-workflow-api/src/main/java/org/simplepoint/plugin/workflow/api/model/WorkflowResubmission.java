package org.simplepoint.plugin.workflow.api.model;

import java.util.List;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.RuntimeForm;

/** Original-version starter form and a linked, immutable source matter. */
public record WorkflowResubmission(
    WorkflowMatter source, RuntimeForm form, List<String> ccUserIds) {}
