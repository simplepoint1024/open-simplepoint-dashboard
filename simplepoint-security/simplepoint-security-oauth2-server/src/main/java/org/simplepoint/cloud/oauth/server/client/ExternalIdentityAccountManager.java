package org.simplepoint.cloud.oauth.server.client;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityLink;
import org.simplepoint.plugin.oidc.api.entity.ExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityLinkRepository;
import org.simplepoint.plugin.oidc.api.repository.ExternalIdentityProviderRepository;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** User-facing operations for listing and removing external account connections. */
@Component
public class ExternalIdentityAccountManager {

  private final ExternalIdentityProviderRepository providerRepository;

  private final ExternalIdentityLinkRepository linkRepository;

  private final ExternalIdentityProviderService providerService;

  /**
   * Creates the external identity account manager.
   *
   * @param providerRepository external identity provider repository
   * @param linkRepository external identity link repository
   * @param providerService external identity provider resolver
   */
  public ExternalIdentityAccountManager(
      final ExternalIdentityProviderRepository providerRepository,
      final ExternalIdentityLinkRepository linkRepository,
      final ExternalIdentityProviderService providerService
  ) {
    this.providerRepository = providerRepository;
    this.linkRepository = linkRepository;
    this.providerService = providerService;
  }

  /**
   * Lists available providers and the user's current external account connections.
   *
   * @param userId local user identifier
   * @return connection state for every available or currently linked provider
   */
  public List<ExternalIdentityConnectionView> connections(final String userId) {
    Map<String, ExternalIdentityLink> links = linkRepository
        .findAllActiveByUserId(userId)
        .stream()
        .collect(Collectors.toMap(
            ExternalIdentityLink::getProviderId,
            Function.identity(),
            (first, ignored) -> first
        ));
    return providerRepository.findAllActive().stream()
        .filter(provider -> Boolean.TRUE.equals(provider.getEnabled())
            || links.containsKey(provider.getId()))
        .map(provider -> toView(provider, links.get(provider.getId())))
        .toList();
  }

  /**
   * Checks whether a user is linked to an external identity provider.
   *
   * @param userId local user identifier
   * @param registrationId external identity provider registration identifier
   * @return {@code true} when an active external identity link exists
   */
  public boolean isLinked(final String userId, final String registrationId) {
    ExternalIdentityProvider provider = provider(registrationId);
    return linkRepository.findActiveByProviderAndUserId(provider.getId(), userId).isPresent();
  }

  /**
   * Resolves an active external identity provider for an explicit link flow.
   *
   * @param registrationId external identity provider registration identifier
   * @return resolved external identity provider configuration
   */
  public ResolvedExternalIdentityProvider providerForLink(
      final String registrationId
  ) {
    return providerService.resolve(registrationId);
  }

  /**
   * Removes a user's active link to an external identity provider.
   *
   * @param userId local user identifier
   * @param registrationId external identity provider registration identifier
   */
  @Transactional(rollbackFor = Exception.class)
  public void unlink(final String userId, final String registrationId) {
    ExternalIdentityProvider provider = provider(registrationId);
    ExternalIdentityLink link = linkRepository
        .findActiveByProviderAndUserId(provider.getId(), userId)
        .orElseThrow(() -> new IllegalArgumentException("该第三方账号尚未绑定"));
    linkRepository.deleteById(link.getId());
  }

  private ExternalIdentityProvider provider(final String registrationId) {
    return providerRepository.findActiveByRegistrationId(registrationId)
        .orElseThrow(() -> new IllegalArgumentException("身份提供商不存在"));
  }

  private ExternalIdentityConnectionView toView(
      final ExternalIdentityProvider provider,
      final ExternalIdentityLink link
  ) {
    return new ExternalIdentityConnectionView(
        provider.getRegistrationId(),
        provider.getDisplayName(),
        provider.getPreset(),
        Boolean.TRUE.equals(provider.getEnabled()),
        link != null,
        link == null ? null : link.getEmailAtLink(),
        link == null ? null : link.getCreatedAt(),
        link == null ? null : link.getLastLoginAt()
    );
  }
}
