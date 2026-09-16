package org.simplepoint.plugin.oidc.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;

/** Persistence contract for external identity-provider configurations. */
public interface ExternalIdentityProviderRepository
    extends BaseRepository<ExternalIdentityProvider, String> {

  /** Finds an active provider by database id. */
  Optional<ExternalIdentityProvider> findActiveById(String id);

  /** Finds an active provider by its OAuth2 registration id. */
  Optional<ExternalIdentityProvider> findActiveByRegistrationId(String registrationId);

  /** Lists enabled providers in login-page order. */
  List<ExternalIdentityProvider> findAllActiveEnabled();

  /** Lists all active providers for account-binding management. */
  List<ExternalIdentityProvider> findAllActive();
}
