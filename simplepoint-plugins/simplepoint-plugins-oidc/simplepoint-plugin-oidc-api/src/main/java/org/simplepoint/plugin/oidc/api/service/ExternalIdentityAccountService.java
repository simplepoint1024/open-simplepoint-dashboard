package org.simplepoint.plugin.oidc.api.service;

import java.util.List;
import java.util.Optional;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityBinding;
import org.simplepoint.plugin.oidc.api.model.ExternalIdentityConnectionView;

/** Local OIDC persistence operations; caller must authenticate and validate the local account. */
public interface ExternalIdentityAccountService {
  /** Finds the effective binding without matching mutable email attributes. */
  Optional<ExternalIdentityBinding> find(String providerId, String subject);

  /** Binds an authenticated subject to a validated account; never transfers an active binding. */
  ExternalIdentityBinding bind(String providerId, String subject, String email, String userId);

  /** Records a validated login only while the exact binding is still active. */
  void recordLogin(ExternalIdentityBinding binding);

  /** Returns connection state, including disabled providers with existing bindings. */
  List<ExternalIdentityConnectionView> connections(String userId);

  /** Checks whether an account is already bound to the given provider registration. */
  boolean isLinked(String userId, String registrationId);

  /** Revokes the effective binding while preserving its history. */
  void unlink(String userId, String registrationId);
}
