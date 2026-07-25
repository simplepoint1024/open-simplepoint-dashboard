package org.simplepoint.plugin.oidc.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityLink;

/** Persistence contract for durable external-identity links. */
public interface ExternalIdentityLinkRepository
    extends BaseRepository<ExternalIdentityLink, String> {

  /** Finds an active binding for a provider and stable external subject. */
  Optional<ExternalIdentityLink> findActiveByProviderAndSubject(
      String providerId,
      String externalSubject
  );

  /** Returns whether a provider has any active account bindings. */
  boolean existsActiveByProviderId(String providerId);
}
