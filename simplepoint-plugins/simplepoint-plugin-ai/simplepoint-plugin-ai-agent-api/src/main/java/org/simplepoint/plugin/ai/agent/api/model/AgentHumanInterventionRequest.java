package org.simplepoint.plugin.ai.agent.api.model;

/**
 * Requests operator input at the next safe Agent checkpoint.
 *
 * @param prompt bounded question or instruction shown to the operator
 */
public record AgentHumanInterventionRequest(String prompt) {
}
