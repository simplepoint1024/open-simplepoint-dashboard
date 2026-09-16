package org.simplepoint.cloud.oauth.server.client;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.simplepoint.plugin.oidc.api.model.ResolvedExternalIdentityProvider;
import org.simplepoint.plugin.oidc.api.service.ExternalIdentityProviderService;
import org.simplepoint.security.entity.User;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Maps an OpenID Connect identity onto a durable local SimplePoint account. */
@Component
public class DatabaseOidcUserService
    implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final OidcUserService delegate = new OidcUserService();

  private final ExternalIdentityProviderService providerService;

  private final ExternalIdentityAccountLinker accountLinker;

  private final ExternalIdentityLinkFlow linkFlow;

  private final ObjectProvider<HttpServletRequest> requestProvider;

  /**
   * Creates the OIDC user service.
   */
  public DatabaseOidcUserService(
      final ExternalIdentityProviderService providerService,
      final ExternalIdentityAccountLinker accountLinker,
      final ExternalIdentityLinkFlow linkFlow,
      final ObjectProvider<HttpServletRequest> requestProvider
  ) {
    this.providerService = providerService;
    this.accountLinker = accountLinker;
    this.linkFlow = linkFlow;
    this.requestProvider = requestProvider;
  }

  @Override
  public OidcUser loadUser(final OidcUserRequest userRequest) {
    OidcUser external = delegate.loadUser(userRequest);
    ResolvedExternalIdentityProvider provider = providerService.resolve(
        userRequest.getClientRegistration().getRegistrationId()
    );
    Map<String, Object> attributes = new LinkedHashMap<>(external.getClaims());
    HttpServletRequest request = requestProvider.getIfAvailable();
    User localUser = request == null
        ? accountLinker.link(provider, attributes)
        : linkFlow.resolve(request, provider, attributes);
    attributes.put(DatabaseOauth2UserService.LOCAL_USER_ID, localUser.getId());
    LinkedHashSet<GrantedAuthority> authorities =
        new LinkedHashSet<>(external.getAuthorities());
    authorities.addAll(localUser.getAuthorities());
    return new DefaultOidcUser(
        authorities,
        external.getIdToken(),
        new OidcUserInfo(attributes),
        DatabaseOauth2UserService.LOCAL_USER_ID
    );
  }
}
