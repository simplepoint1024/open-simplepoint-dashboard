package org.simplepoint.cloud.oauth.server.module;

import java.util.Set;
import java.util.TreeSet;
import org.simplepoint.cloud.oauth.server.configuration.AuthorizationServerConfiguration;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModuleHttpRoutes;
import org.simplepoint.platform.http.servlet.ControllerHttpRoutes;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/** Derives native protocol routes from the same pinned framework settings used at runtime. */
public final class AuthorizationHttpRoutes implements ModuleHttpRoutes {
  /** Metadata only: no authentication components or connections are created. */
  public AuthorizationHttpRoutes() {}

  @Override
  public Set<String> roots(ModuleDescriptor module) {
    var roots = new TreeSet<>(new ControllerHttpRoutes().roots(module));
    AuthorizationServerSettings.builder().build().getSettings().values().stream()
        .filter(value -> value instanceof String path && path.startsWith("/"))
        .map(String.class::cast)
        .forEach(roots::add);
    roots.add(OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
    String callback = OAuth2LoginAuthenticationFilter.DEFAULT_FILTER_PROCESSES_URI;
    roots.add(callback.substring(0, callback.indexOf("/*")));
    roots.add(AuthorizationServerConfiguration.LOGOUT_PATH);
    // SAS 7.0.2 keeps these RFC 8414 / OIDC Discovery paths private and non-configurable.
    // The adapter's real filter tests pin the public protocol contract; no reflection or
    // native filter construction is allowed during metadata discovery.
    roots.add("/.well-known/oauth-authorization-server");
    roots.add("/.well-known/openid-configuration");
    String assets =
        new String(
            org.simplepoint.platform.composition.ModuleResources.read(
                module.id(), module.configuration().getClassLoader(), "http-assets.imports"),
            java.nio.charset.StandardCharsets.UTF_8);
    assets.lines().filter(path -> !path.isBlank()).forEach(roots::add);
    return Set.copyOf(roots);
  }
}
