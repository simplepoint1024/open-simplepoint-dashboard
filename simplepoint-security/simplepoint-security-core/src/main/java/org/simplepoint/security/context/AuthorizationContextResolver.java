package org.simplepoint.security.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.springframework.http.HttpHeaders;

/**
 * AuthorizationContextResolver is responsible for resolving the authorization context for a given user ID and HTTP
 * headers. It utilizes a cache to store and retrieve authorization contexts, and if the context is not found in the
 * cache, it calculates it using the provided AuthorizationContextService.
 *
 * <p>AuthorizationContextResolver 负责解析给定用户 ID 和 HTTP 头的授权上下文。它利用缓存来存储和检索授权上下文，如果在缓存中未找到上下文，则使用提供的 AuthorizationContextService 进行计算。</p>
 */
public class AuthorizationContextResolver {

  private final String cacheKeyPrefix;

  private final CacheService cacheService;

  private final AuthorizationContextService contextService;

  private final URI userInfoEndpointUri;

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Constructs an AuthorizationContextResolver with the specified cache save function, cache load function, and context service.
   *
   * @param cacheKeyPrefix      the prefix to use for cache keys when saving and loading authorization contexts
   * @param cacheService        the CacheService used to save and load authorization contexts from the cache
   * @param userInfoEndpointUri the URI of the user info endpoint to retrieve user information using the access token
   * @param contextService      the AuthorizationContextService used to calculate the authorization context if it is not found in the cache
   */
  public AuthorizationContextResolver(
      String cacheKeyPrefix,
      CacheService cacheService,
      AuthorizationContextService contextService,
      URI userInfoEndpointUri
  ) {
    this.cacheKeyPrefix = cacheKeyPrefix;
    this.cacheService = cacheService;
    this.contextService = contextService;
    this.userInfoEndpointUri = userInfoEndpointUri;
  }

  /**
   * Loads the authorization context from the cache using the provided context ID.
   *
   * @param contextId the ID of the authorization context to load from the cache
   * @return the loaded AuthorizationContext, or null if not found in the cache
   */
  public AuthorizationContext load(String contextId) {
    if (contextId == null || contextId.isBlank()) {
      return null;
    }
    return cacheService.get(cacheKeyPrefix + contextId, AuthorizationContext.class);
  }

  /**
   * Resolves the authorization context for a given user ID and HTTP headers.
   *
   * @param httpHeaders a map of HTTP headers that may contain additional information for resolving the context
   * @return the resolved AuthorizationContext
   * @throws RuntimeException if the authorization context cannot be resolved
   */
  public AuthorizationContext resolve(Map<String, String> httpHeaders) {
    final String authorization = getHeader(httpHeaders, HttpHeaders.AUTHORIZATION);
    if (authorization != null && !authorization.isBlank()) {
      final String contextId = getHeader(httpHeaders, "X-Context-Id");
      final String tenantId = getHeader(httpHeaders, "X-Tenant-Id");
      Map<String, Object> userInfo = getUserInfo(authorization);
      final String userId = (String) userInfo.get("sub");
      final Map<String, String> attributes = new HashMap<>();
      attributes.put("X-User-Id", userId);
      httpHeaders.forEach((k, v) -> {
        if (k != null && k.regionMatches(true, 0, "X-", 0, 2)) {
          attributes.put(normalizeHeaderName(k), v);
        }
      });
      // 先从缓存加载，加载不到再计算并保存到缓存
      AuthorizationContext authorizationContext = load(contextId);
      if (authorizationContext != null) {
        return authorizationContext;
      }
      authorizationContext = contextService.calculate(tenantId, userId, contextId, attributes);
      if (authorizationContext != null) {
        if (contextId != null && !contextId.isBlank()) {
          cacheService.put(cacheKeyPrefix + contextId, authorizationContext, 2 * 60 * 60); // 设置过期时间为 2 小时
        }
        return authorizationContext;
      }
      throw new RuntimeException("无法解析授权上下文");
    }
    return null;
  }

  private static String getHeader(Map<String, String> httpHeaders, String headerName) {
    for (Map.Entry<String, String> entry : httpHeaders.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private static String normalizeHeaderName(String headerName) {
    if ("X-Tenant-Id".equalsIgnoreCase(headerName)) {
      return "X-Tenant-Id";
    }
    if ("X-Context-Id".equalsIgnoreCase(headerName)) {
      return "X-Context-Id";
    }
    if ("X-User-Id".equalsIgnoreCase(headerName)) {
      return "X-User-Id";
    }
    return headerName;
  }

  /**
   * Retrieves user information using the access token.
   *
   * @param authorizationHeader the access token to use for retrieving user information
   * @return a map containing user information
   * @throws RuntimeException if there is an error while retrieving user information
   */
  protected Map<String, Object> getUserInfo(String authorizationHeader) {
    HttpRequest request = HttpRequest.newBuilder(userInfoEndpointUri)
        .POST(HttpRequest.BodyPublishers.noBody())
        .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
        .build();

    HttpClient httpClient = HttpClient.newHttpClient();
    try {
      HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return objectMapper.readValue(send.body(), new TypeReference<HashMap<String, Object>>() {
      });
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
