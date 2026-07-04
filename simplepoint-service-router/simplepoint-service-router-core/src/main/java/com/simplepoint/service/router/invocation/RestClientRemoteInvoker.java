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

  /**
   * Creates a RestClient based remote invoker.
   *
   * @param restClient HTTP client
   * @param properties router properties
   */
  public RestClientRemoteInvoker(final RestClient restClient, final ServiceRouterProperties properties) {
    this.restClient = restClient;
    this.properties = properties;
  }

  @Override
  public RemoteResponse invoke(final ServiceRoute route, final RemoteRequest request) {
    URI uri = route.uri().resolve(properties.getProvider().getExposePath());
    RestClient.RequestBodySpec spec = restClient.post().uri(uri);
    String token = properties.getInternalAuth().getToken();
    if (StringUtils.hasText(token)) {
      spec.header(properties.getInternalAuth().getHeaderName(), token);
    }
    return spec.body(request).retrieve().body(RemoteResponse.class);
  }
}
