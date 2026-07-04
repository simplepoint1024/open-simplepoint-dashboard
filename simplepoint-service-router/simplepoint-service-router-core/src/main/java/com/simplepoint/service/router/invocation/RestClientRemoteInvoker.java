package com.simplepoint.service.router.invocation;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * RestClient based remote invoker.
 */
public class RestClientRemoteInvoker implements RemoteInvoker {

  private final RestClient restClient;

  private final ServiceRouterProperties properties;

  private final ServiceRouterAccessTokenProvider accessTokenProvider;

  /**
   * Creates a RestClient based remote invoker.
   *
   * @param restClient HTTP client
   * @param properties router properties
   */
  public RestClientRemoteInvoker(final RestClient restClient, final ServiceRouterProperties properties) {
    this(restClient, properties, new NoOpServiceRouterAccessTokenProvider());
  }

  /**
   * Creates a RestClient based remote invoker.
   *
   * @param restClient HTTP client
   * @param properties router properties
   * @param accessTokenProvider service access token provider
   */
  public RestClientRemoteInvoker(
      final RestClient restClient,
      final ServiceRouterProperties properties,
      final ServiceRouterAccessTokenProvider accessTokenProvider
  ) {
    this.restClient = restClient;
    this.properties = properties;
    this.accessTokenProvider = accessTokenProvider;
  }

  @Override
  public RemoteResponse invoke(final ServiceRoute route, final RemoteRequest request) {
    final URI uri = route.uri().resolve(properties.getProvider().getExposePath());
    final RestClient.RequestBodySpec spec = restClient.post().uri(uri);
    if (isOauth2Mode()) {
      final String accessToken = accessTokenProvider.getAccessToken();
      if (StringUtils.hasText(accessToken)) {
        spec.header("Authorization", "Bearer " + accessToken);
      }
    } else {
      final String token = properties.getInternalAuth().getToken();
      if (StringUtils.hasText(token)) {
        spec.header(properties.getInternalAuth().getHeaderName(), token);
      }
    }
    return spec.body(request).retrieve().body(RemoteResponse.class);
  }

  private boolean isOauth2Mode() {
    return "oauth2".equalsIgnoreCase(properties.getInternalAuth().getMode());
  }
}
