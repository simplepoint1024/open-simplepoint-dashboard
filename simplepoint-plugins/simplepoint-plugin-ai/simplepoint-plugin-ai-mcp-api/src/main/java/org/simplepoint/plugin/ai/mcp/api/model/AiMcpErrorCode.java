package org.simplepoint.plugin.ai.mcp.api.model;

import java.util.Locale;

/**
 * Stable, language-neutral MCP diagnostics exposed by public APIs.
 */
public enum AiMcpErrorCode {
  AI_MCP_DISCOVERY_FAILED,
  AI_MCP_OAUTH_ACCESS_DENIED,
  AI_MCP_OAUTH_AUTHORIZATION_EXPIRED,
  AI_MCP_OAUTH_AUTHORIZATION_FAILED;

  /**
   * Converts a persisted discovery diagnostic to a safe public code.
   *
   * @param diagnostic internal diagnostic text
   * @return stable public error code, or {@code null} when no error exists
   */
  public static AiMcpErrorCode fromDiscoveryDiagnostic(final String diagnostic) {
    if (diagnostic == null || diagnostic.isBlank()) {
      return null;
    }
    return AI_MCP_DISCOVERY_FAILED;
  }

  /**
   * Converts a persisted provider OAuth diagnostic to a safe public code.
   *
   * @param diagnostic provider or internal diagnostic text
   * @return stable public error code, or {@code null} when no error exists
   */
  public static AiMcpErrorCode fromOauthDiagnostic(final String diagnostic) {
    if (diagnostic == null || diagnostic.isBlank()) {
      return null;
    }
    try {
      AiMcpErrorCode code = valueOf(diagnostic.trim());
      if (code != AI_MCP_DISCOVERY_FAILED) {
        return code;
      }
    } catch (IllegalArgumentException ignored) {
      // Legacy rows contain provider or internal prose; classify those below.
    }
    String normalized = diagnostic.toLowerCase(Locale.ROOT);
    if (normalized.contains("access_denied")
        || normalized.contains("access denied")) {
      return AI_MCP_OAUTH_ACCESS_DENIED;
    }
    if (normalized.contains("invalid_grant")
        || normalized.contains("expired")
        || normalized.contains("already been used")) {
      return AI_MCP_OAUTH_AUTHORIZATION_EXPIRED;
    }
    return AI_MCP_OAUTH_AUTHORIZATION_FAILED;
  }
}
