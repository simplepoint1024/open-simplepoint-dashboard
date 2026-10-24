package org.simplepoint.security.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.cache.CacheService;
import org.simplepoint.core.AuthorizationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.StringUtils;

/**
 * AuthorizationContextResolver is responsible for resolving the authorization context for a given
 * user ID and HTTP headers. It utilizes a cache to store and retrieve authorization contexts, and
 * if the context is not found in the cache, it calculates it using the provided
 * AuthorizationContextService.
 *
 * <p>AuthorizationContextResolver 负责解析给定用户 ID 和 HTTP 头的授权上下文。它利用缓存来存储和检索授权上下文，如果在缓存中未找到上下文，则使用提供的
 * AuthorizationContextService 进行计算。
 */
public class AuthorizationContextResolver {

  private final String cacheKeyPrefix;

  private final CacheService cacheService;

  private final AuthorizationContextService contextService;

  private final URI userInfoEndpointUri;
  private final Clock clock;

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Constructs an AuthorizationContextResolver with the specified cache save function, cache load
   * function, and context service.
   *
   * @param cacheKeyPrefix the prefix to use for cache keys when saving and loading authorization
   *     contexts
   * @param cacheService the CacheService used to save and load authorization contexts from the
   *     cache
   * @param userInfoEndpointUri the URI of the user info endpoint to retrieve user information using
   *     the access token
   * @param contextService the AuthorizationContextService used to calculate the authorization
   *     context if it is not found in the cache
   */
  public AuthorizationContextResolver(
      String cacheKeyPrefix,
      CacheService cacheService,
      AuthorizationContextService contextService,
      URI userInfoEndpointUri) {
    this(cacheKeyPrefix, cacheService, contextService, userInfoEndpointUri, Clock.systemUTC());
  }

  AuthorizationContextResolver(
      String cacheKeyPrefix,
      CacheService cacheService,
      AuthorizationContextService contextService,
      URI userInfoEndpointUri,
      Clock clock) {
    this.cacheKeyPrefix = cacheKeyPrefix;
    this.cacheService = cacheService;
    this.contextService = contextService;
    this.userInfoEndpointUri = userInfoEndpointUri;
    this.clock = Objects.requireNonNull(clock);
  }

  /** Creates request authorization that accepts only an already authenticated subject. */
  public static AuthorizationContextResolver forAuthenticatedSubjects(
      CacheService cache, AuthorizationContextService service) {
    return new AuthorizationContextResolver(
        "simplepoint:security:authorization-context:",
        Objects.requireNonNull(cache),
        Objects.requireNonNull(service),
        null);
  }

  /**
   * Resolves the authorization context for a given user ID and HTTP headers.
   *
   * @param httpHeaders a map of HTTP headers that may contain additional information for resolving
   *     the context
   * @return the resolved AuthorizationContext
   * @throws RuntimeException if the authorization context cannot be resolved
   */
  public AuthorizationContext resolve(Map<String, String> httpHeaders) {
    if (userInfoEndpointUri == null) {
      throw new AuthenticationServiceException("This resolver requires an authenticated subject");
    }
    final String authorization = getHeader(httpHeaders, HttpHeaders.AUTHORIZATION);
    if (authorization != null && !authorization.isBlank()) {
      Map<String, Object> userInfo = getUserInfo(authorization);
      final String userId = resolveSubject(userInfo);
      return resolveAuthenticated(userId, httpHeaders);
    }
    return null;
  }

  /** Called only after token validation; client context IDs are hints, never cache credentials. */
  public AuthorizationContext resolveAuthenticated(String userId, Map<String, String> headers) {
    try {
      return resolveVerifiedPolicy(userId, headers);
    } catch (AuthorizationContextRejection rejected) {
      if (rejected.reason() == AuthorizationContextRejection.Reason.ACCESS_DENIED) {
        throw new AccessDeniedException("Authorization context access denied", rejected);
      }
      throw new BadCredentialsException("Authentication is required", rejected);
    }
  }

  private AuthorizationContext resolveVerifiedPolicy(String userId, Map<String, String> headers) {
    if (!StringUtils.hasText(userId)) {
      throw new BadCredentialsException("认证主体缺少用户标识");
    }
    String tenantId = trim(getHeader(headers, "X-Tenant-Id"));
    String roleId = trim(getHeader(headers, "X-Role-Id"));
    final String contextId = trim(getHeader(headers, "X-Context-Id"));
    Long version = contextService.checkedCurrentVersion(tenantId);
    Long subjectVersion = contextService.checkedCurrentSubjectVersion(userId);
    String key =
        version == null || subjectVersion == null
            ? null
            : verifiedCacheKey(userId, tenantId, roleId, version, subjectVersion);
    AuthorizationContext cached =
        key == null ? null : cacheService.get(key, AuthorizationContext.class);
    if (cached != null
        && temporallyValid(cached)
        && Objects.equals(userId, cached.getUserId())
        && Objects.equals(tenantId, trim(cached.getAttribute("X-Tenant-Id")))
        && Objects.equals(roleId, trim(cached.getAttribute("X-Role-Id")))
        && Objects.equals(version, cached.getVersion())
        && Objects.equals(
            String.valueOf(subjectVersion), cached.getAttribute("X-Subject-Version"))) {
      return cached;
    }
    Map<String, String> attributes = new HashMap<>();
    attributes.put("X-User-Id", userId);
    if (tenantId != null) {
      attributes.put("X-Tenant-Id", tenantId);
    }
    if (roleId != null) {
      attributes.put("X-Role-Id", roleId);
    }
    AuthorizationContext resolved =
        contextService.checkedCalculate(tenantId, userId, contextId, attributes);
    // A query can span the deadline. Re-evaluate once rather than return an already expired policy.
    if (resolved != null && !temporallyValid(resolved)) {
      resolved = contextService.checkedCalculate(tenantId, userId, contextId, attributes);
    }
    if (resolved == null || !userId.equals(resolved.getUserId())) {
      throw new BadCredentialsException("无法解析授权上下文");
    }
    if (!temporallyValid(resolved)) {
      throw new AuthenticationServiceException("Authorization policy expired during calculation");
    }
    if ((key != null && !Objects.equals(tenantId, trim(resolved.getAttribute("X-Tenant-Id"))))
        || (roleId != null && !Objects.equals(roleId, trim(resolved.getAttribute("X-Role-Id"))))) {
      throw new BadCredentialsException("授权上下文与请求范围不匹配");
    }
    // If policy changed during calculation, do not cache under the earlier version.
    if (key != null
        && Objects.equals(version, resolved.getVersion())
        && Objects.equals(subjectVersion, contextService.checkedCurrentSubjectVersion(userId))) {
      resolved.mergeAttributes(Map.of("X-Subject-Version", String.valueOf(subjectVersion)));
      long ttl =
          resolved.getValidUntil() == null
              ? 2 * 60 * 60
              : Math.min(
                  2 * 60 * 60,
                  Duration.between(clock.instant(), resolved.getValidUntil()).getSeconds());
      if (ttl > 0) {
        cacheService.put(key, resolved, ttl);
      }
    }
    if (!temporallyValid(resolved)) {
      throw new AuthenticationServiceException("Authorization policy expired during calculation");
    }
    return resolved;
  }

  private boolean temporallyValid(AuthorizationContext context) {
    return context.getValidUntil() == null || clock.instant().isBefore(context.getValidUntil());
  }

  private String verifiedCacheKey(
      String userId, String tenantId, String roleId, Long version, Long subjectVersion) {
    try {
      byte[] identity =
          objectMapper.writeValueAsBytes(
              new Object[] {userId, tenantId, roleId, version, subjectVersion});
      // v8 also excludes contexts built before owner-only capabilities were enforced.
      // Tenant entitlement deadlines still bound every cached context.
      return cacheKeyPrefix
          + "verified-v8:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
    } catch (IOException | NoSuchAlgorithmException error) {
      throw new AuthenticationServiceException("无法生成权限缓存标识", error);
    }
  }

  private static String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String resolveSubject(Map<String, Object> userInfo) {
    Object subject = userInfo == null ? null : userInfo.get("sub");
    if (subject instanceof String userId && StringUtils.hasText(userId)) {
      return userId;
    }
    throw new BadCredentialsException("无法解析认证主体");
  }

  private static String getHeader(Map<String, String> httpHeaders, String headerName) {
    for (Map.Entry<String, String> entry : httpHeaders.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(headerName)) {
        return entry.getValue();
      }
    }
    return null;
  }

  /**
   * Retrieves user information using the access token.
   *
   * @param authorizationHeader the access token to use for retrieving user information
   * @return a map containing user information
   * @throws RuntimeException if there is an error while retrieving user information
   */
  protected Map<String, Object> getUserInfo(String authorizationHeader) {
    HttpRequest request =
        HttpRequest.newBuilder(userInfoEndpointUri)
            .POST(HttpRequest.BodyPublishers.noBody())
            .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
            .build();

    HttpClient httpClient = HttpClient.newHttpClient();
    try {
      HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (send.statusCode() >= 400) {
        throw new BadCredentialsException("无法获取用户信息");
      }
      return objectMapper.readValue(send.body(), new TypeReference<HashMap<String, Object>>() {});
    } catch (IOException e) {
      throw new AuthenticationServiceException("无法获取用户信息", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AuthenticationServiceException("无法获取用户信息", e);
    }
  }
}
