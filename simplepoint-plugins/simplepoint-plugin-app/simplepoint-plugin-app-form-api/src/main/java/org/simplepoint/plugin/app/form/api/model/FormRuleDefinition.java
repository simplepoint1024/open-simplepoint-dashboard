package org.simplepoint.plugin.app.form.api.model;

/** Declarative component rule evaluated consistently by client and server. */
public record FormRuleDefinition(
    String ruleId,
    String targetComponentId,
    FormRuleEffect effect,
    FormCondition condition
) {
}
