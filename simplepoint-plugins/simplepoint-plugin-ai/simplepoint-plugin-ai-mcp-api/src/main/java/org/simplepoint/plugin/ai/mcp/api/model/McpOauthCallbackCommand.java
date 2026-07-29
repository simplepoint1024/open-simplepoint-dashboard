package org.simplepoint.plugin.ai.mcp.api.model;

/**
 * OAuth redirect parameters relayed by the authenticated workbench page.
 *
 * @param code authorization code
 * @param state one-time OAuth state
 * @param error optional authorization error
 * @param errorDescription optional authorization error description
 */
public record McpOauthCallbackCommand(
    String code,
    String state,
    String error,
    String errorDescription
) {
}
