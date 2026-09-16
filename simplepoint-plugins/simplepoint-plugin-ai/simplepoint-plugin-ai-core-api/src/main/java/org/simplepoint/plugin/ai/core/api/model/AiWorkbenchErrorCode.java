package org.simplepoint.plugin.ai.core.api.model;

/**
 * Stable, language-neutral errors returned by AI workbench core operations.
 *
 * <p>Codes are grouped by resource so additional workbench domains can extend
 * this contract without exposing service exception messages.</p>
 */
public enum AiWorkbenchErrorCode {
  AI_MODEL_REQUEST_INVALID,
  AI_MODEL_OPERATION_UNAVAILABLE,
  AI_MODEL_OPERATION_FAILED,
  AI_API_KEY_REQUEST_INVALID,
  AI_API_KEY_OPERATION_UNAVAILABLE,
  AI_API_KEY_OPERATION_FAILED,
  AI_BILLING_RANGE_INVALID,
  AI_BILLING_QUERY_FAILED,
  AI_MODEL_DEBUG_REQUEST_INVALID,
  AI_MODEL_DEBUG_OPERATION_UNAVAILABLE,
  AI_MODEL_DEBUG_PROVIDER_FAILED,
  AI_MODEL_DEBUG_BUSY,
  AI_MODEL_DEBUG_GENERATION_FAILED,
  AI_MODEL_DEBUG_FAILED
}
