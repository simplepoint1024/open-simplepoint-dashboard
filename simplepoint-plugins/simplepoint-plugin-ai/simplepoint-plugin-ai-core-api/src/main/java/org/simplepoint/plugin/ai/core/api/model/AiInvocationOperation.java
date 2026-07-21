package org.simplepoint.plugin.ai.core.api.model;

/** AI runtime operation recorded in the usage ledger. */
public enum AiInvocationOperation {
  GENERATION,
  EMBEDDING,
  RERANK
}
