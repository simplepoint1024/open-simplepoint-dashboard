package org.simplepoint.cloud.oauth.server.client;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Map;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/** Maps a generic OAuth2 user response onto a durable local SimplePoint account. */
@Component
public class DatabaseOauth2UserService
    implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  /** Synthetic claim used as the authenticated principal name. */
  public static final String LOCAL_USER_ID = "simplepoint_local_user_id";

  private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();

  private final ExternalIdentityProviderService providerService;

  private final ExternalIdentityAccountLinker accountLinker;

  private final ExternalIdentityLinkFlow linkFlow;

  private final ObjectProvider<HttpServletRequest> requestProvider;

  private final GithubEmailAttributeEnricher githubEmailAttributeEnricher;

  /**
   * Creates the user service.
   */
  public DatabaseOauth2UserService(
      final ExternalIdentityProviderService providerService,
      final ExternalIdentityAccountLinker accountLinker,
      final GithubEmailAttributeEnricher githubEmailAttributeEnricher,
      final ExternalIdentityLinkFlow linkFlow,
      final ObjectProvider<HttpServletRequest> requestProvider
  ) {
    this.providerService = providerService;
    this.accountLinker = accountLinker;
    this.githubEmailAttributeEnricher = githubEmailAttributeEnricher;
    this.linkFlow = linkFlow;
    this.requestProvider = requestProvider;
  }

  @Override
  public OAuth2User loadUser(final OAuth2UserRequest userRequest) {
    OAuth2User external = delegate.loadUser(userRequest);
    ResolvedExternalIdentityProvider provider = providerService.resolve(
        userRequest.getClientRegistration().getRegistrationId()
    );
    Map<String, Object> attributes = githubEmailAttributeEnricher.enrich(
        userRequest,
        provider,
        external.getAttributes()
    );
    HttpServletRequest request = requestProvider.getIfAvailable();
    User localUser = request == null
        ? accountLinker.link(provider, attributes)
        : linkFlow.resolve(request, provider, attributes);
    attributes.put(LOCAL_USER_ID, localUser.getId());
    LinkedHashSet<GrantedAuthority> authorities =
        new LinkedHashSet<>(external.getAuthorities());
    authorities.addAll(localUser.getAuthorities());
    return new DefaultOAuth2User(authorities, attributes, LOCAL_USER_ID);
  }
}
