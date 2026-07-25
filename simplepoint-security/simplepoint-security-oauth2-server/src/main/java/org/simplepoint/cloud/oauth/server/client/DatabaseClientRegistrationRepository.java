package org.simplepoint.cloud.oauth.server.client;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.plugin.oidc.service.support.ExternalIdentityClientRegistrationFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

/**
 * Database-backed and dynamically refreshed OAuth2 client-registration repository.
 *
 * <p>Provider changes become effective without restarting the authorization service.</p>
 */
@Slf4j
@Component
public class DatabaseClientRegistrationRepository
    implements ClientRegistrationRepository, Iterable<ClientRegistration> {

  private final ExternalIdentityProviderService providerService;

  private final ExternalIdentityClientRegistrationFactory registrationFactory;

  private final Map<String, CachedRegistration> cache = new ConcurrentHashMap<>();

  /**
   * Creates the repository.
   */
  public DatabaseClientRegistrationRepository(
      final ExternalIdentityProviderService providerService,
      final ExternalIdentityClientRegistrationFactory registrationFactory
  ) {
    this.providerService = providerService;
    this.registrationFactory = registrationFactory;
  }

  @Override
  public ClientRegistration findByRegistrationId(final String registrationId) {
    ResolvedExternalIdentityProvider provider = providerService.resolve(registrationId);
    Instant version = provider.updatedAt();
    CachedRegistration cached = cache.get(registrationId);
    if (cached != null && java.util.Objects.equals(cached.version(), version)) {
      return cached.registration();
    }
    ClientRegistration registration = registrationFactory.build(provider);
    cache.put(registrationId, new CachedRegistration(version, registration));
    return registration;
  }

  @Override
  public Iterator<ClientRegistration> iterator() {
    List<ClientRegistration> registrations = providerService.enabledProviders().stream()
        .map(provider -> safeFind(provider.registrationId()))
        .filter(java.util.Objects::nonNull)
        .toList();
    return registrations.iterator();
  }

  private ClientRegistration safeFind(final String registrationId) {
    try {
      return findByRegistrationId(registrationId);
    } catch (RuntimeException ex) {
      log.warn(
          "Skipping invalid external identity-provider registration '{}': {}",
          registrationId,
          ex.getMessage()
      );
      return null;
    }
  }

  private record CachedRegistration(
      Instant version,
      ClientRegistration registration
  ) {
  }
}
