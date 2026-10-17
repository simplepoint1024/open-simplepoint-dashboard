package org.simplepoint.plugin.app.form.api.service;

import org.simplepoint.plugin.app.form.api.model.ActivateApplicationPageBinding;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageBindingTarget;
import org.simplepoint.plugin.app.form.api.model.ApplicationPageTarget;

/** APP catalog boundary used by the form domain without exposing service entities. */
public interface ApplicationPageAccessPort {

  /** Locks and resolves a tenant-owned page before publication state changes. */
  ApplicationPageTarget requirePageForUpdate(
      String tenantId,
      String applicationId,
      String pageId
  );

  /** Atomically switches the APP catalog page to an active form/resource binding. */
  void activateBinding(ActivateApplicationPageBinding binding);

  /** Atomically closes the runtime entry after the caller has locked and checked its form. */
  default void offlineBinding(String tenantId, String applicationId, String pageId, boolean completed) {
    throw new UnsupportedOperationException("Resource lifecycle adapter is required");
  }

  /** Resolves an active page binding without exposing mutable APP catalog entities. */
  ApplicationPageBindingTarget requireActiveBinding(
      String tenantId,
      String applicationId,
      String pageId
  );
}
