package org.simplepoint.plugin.ai.knowledge.api.model;

/** Stable, language-neutral knowledge diagnostics exposed by public APIs. */
public enum AiKnowledgeErrorCode {
  AI_KNOWLEDGE_INDEX_FAILED;

  /**
   * Converts a persisted indexing diagnostic to a safe public code.
   *
   * @param diagnostic internal diagnostic text
   * @return stable public error code, or {@code null} when no error exists
   */
  public static AiKnowledgeErrorCode fromDiagnostic(final String diagnostic) {
    if (diagnostic == null || diagnostic.isBlank()) {
      return null;
    }
    return AI_KNOWLEDGE_INDEX_FAILED;
  }
}
