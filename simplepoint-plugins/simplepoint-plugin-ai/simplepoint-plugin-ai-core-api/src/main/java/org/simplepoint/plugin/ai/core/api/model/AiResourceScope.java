package org.simplepoint.plugin.ai.core.api.model;

/**
 * Ownership scope for AI provider and model resources.
 */
public enum AiResourceScope {
  /**
   * Platform-managed resource shared by tenants.
   */
  SYSTEM,

  /**
   * Resource owned by one organization tenant.
   */
  TENANT
}
