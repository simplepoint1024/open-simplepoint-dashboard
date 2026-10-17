package org.simplepoint.plugin.app.form.api.service;

/** Resolves application ownership without coupling the form implementation to APP catalog storage. */
public interface ApplicationAccessPort {

  /** Rejects when the application is not active in the given tenant. */
  void requireApplication(String tenantId, String applicationId);

  /**
   * Rejects an inaccessible application and serializes form collection mutations for it.
   *
   * <p>Remote or non-relational adapters may retain the default ownership check and enforce
   * collection limits with their own atomic primitive.</p>
   */
  default void requireApplicationForUpdate(String tenantId, String applicationId) {
    requireApplication(tenantId, applicationId);
  }
}
