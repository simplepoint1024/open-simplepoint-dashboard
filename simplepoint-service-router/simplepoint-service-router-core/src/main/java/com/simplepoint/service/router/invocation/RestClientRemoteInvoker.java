package com.simplepoint.service.router.invocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** RestClient based remote invoker. */
public class RestClientRemoteInvoker implements RemoteInvoker {

  private final RestClient restClient;

  private final ServiceRouterProperties properties;

  private final ServiceRouterAccessTokenProvider accessTokenProvider;

  private final ServiceRouterJsonCodec codec;

  /**
   * Creates a RestClient based remote invoker.
   *
   * @param restClient HTTP client
   * @param properties router properties
   */
  public RestClientRemoteInvoker(
      final RestClient restClient, final ServiceRouterProperties properties) {
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
      final ServiceRouterAccessTokenProvider accessTokenProvider) {
    this(restClient, properties, accessTokenProvider, new ObjectMapper());
  }

  /**
   * Creates an invoker using the application's JSON modules and the precise RPC codec.
   *
   * @param restClient HTTP client
   * @param properties router properties
   * @param accessTokenProvider service access token provider
   * @param objectMapper application JSON configuration
   */
  public RestClientRemoteInvoker(
      final RestClient restClient,
      final ServiceRouterProperties properties,
      final ServiceRouterAccessTokenProvider accessTokenProvider,
      final ObjectMapper objectMapper) {
    this.restClient = restClient;
    this.properties = properties;
    this.accessTokenProvider = accessTokenProvider;
    this.codec = new ServiceRouterJsonCodec(objectMapper);
  }

  @Override
  public RemoteResponse invoke(final ServiceRoute route, final RemoteRequest request) {
    final URI uri = route.uri().resolve(properties.getProvider().getExposePath());
    if (!isOauth2Mode()) {
      return invokeAuthenticated(uri, request, null);
    }
    final String accessToken = accessTokenProvider.getAccessToken();
    try {
      return invokeAuthenticated(uri, request, accessToken);
    } catch (HttpClientErrorException.Unauthorized rejected) {
      // HTTP 401 rejects authentication before RPC dispatch. Never replay timeouts, 403s,
      // domain failures or ambiguous writes; a replacement credential gets one attempt only.
      if (!StringUtils.hasText(accessToken)) {
        throw rejected;
      }
      accessTokenProvider.invalidate(accessToken);
      final String replacement = accessTokenProvider.getAccessToken();
      if (!StringUtils.hasText(replacement) || accessToken.equals(replacement)) {
        throw rejected;
      }
      return invokeAuthenticated(uri, request, replacement);
    }
  }

  private RemoteResponse invokeAuthenticated(
      final URI uri, final RemoteRequest request, final String accessToken) {
    final RestClient.RequestBodySpec spec = restClient.post().uri(uri);
    if (isOauth2Mode()) {
      if (StringUtils.hasText(accessToken)) {
        spec.header("Authorization", "Bearer " + accessToken);
      }
    } else {
      final String token = properties.getInternalAuth().getToken();
      if (StringUtils.hasText(token)) {
        spec.header(properties.getInternalAuth().getHeaderName(), token);
      }
    }
    return codec.decodeResponse(spec.contentType(MediaType.APPLICATION_JSON)
        .body(codec.encode(request)).retrieve().body(byte[].class));
  }

  private boolean isOauth2Mode() {
    return "oauth2".equalsIgnoreCase(properties.getInternalAuth().getMode());
  }
}
