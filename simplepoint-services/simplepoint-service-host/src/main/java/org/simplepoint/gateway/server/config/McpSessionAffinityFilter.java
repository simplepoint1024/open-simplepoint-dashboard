package org.simplepoint.gateway.server.config;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Routes a stateful MCP session to the Gateway replica that created it.
 */
@Component
public class McpSessionAffinityFilter implements GlobalFilter, Ordered {

  private static final int BEFORE_REACTIVE_LOAD_BALANCER = 10149;

  private static final String SESSION_HEADER = "Mcp-Session-Id";

  private final ReactiveStringRedisTemplate redisTemplate;

  private final McpSessionAffinityProperties properties;

  /**
   * Creates the MCP session affinity filter.
   */
  public McpSessionAffinityFilter(
      final ReactiveStringRedisTemplate redisTemplate,
      final McpSessionAffinityProperties properties
  ) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
  }

  @Override
  public Mono<Void> filter(
      final ServerWebExchange exchange,
      final GatewayFilterChain chain
  ) {
    if (!properties.isEnabled()
        || !exchange.getRequest().getPath().value().startsWith("/mcp/")) {
      return chain.filter(exchange);
    }
    String sessionId = normalizeSessionId(
        exchange.getRequest().getHeaders().getFirst(SESSION_HEADER)
    );
    if (sessionId == null) {
      return routeAndRegister(exchange, chain);
    }
    String key = redisKey(sessionId);
    return redisTemplate.opsForValue().get(key)
        .onErrorResume(exception -> Mono.empty())
        .flatMap(route -> routeToMappedInstance(exchange, chain, key, route)
            .thenReturn(Boolean.TRUE))
        .switchIfEmpty(routeAndRegister(exchange, chain)
            .thenReturn(Boolean.FALSE))
        .then();
  }

  @Override
  public int getOrder() {
    return BEFORE_REACTIVE_LOAD_BALANCER;
  }

  private Mono<Void> routeToMappedInstance(
      final ServerWebExchange exchange,
      final GatewayFilterChain chain,
      final String key,
      final String route
  ) {
    URI current = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
    URI mapped = mappedUri(current, route);
    if (mapped == null) {
      return deleteQuietly(key)
          .then(routeAndRegister(exchange, chain));
    }
    exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, mapped);
    return chain.filter(exchange)
        .onErrorResume(exception ->
            deleteQuietly(key).then(completeBadGateway(exchange, exception)))
        .then(Mono.defer(() -> {
          HttpStatusCode status = exchange.getResponse().getStatusCode();
          if (isStaleMappingStatus(status)) {
            return deleteQuietly(key);
          }
          return redisTemplate.expire(key, affinityTtl())
              .onErrorReturn(Boolean.FALSE)
              .then();
        }));
  }

  private static Mono<Void> completeBadGateway(
      final ServerWebExchange exchange,
      final Throwable exception
  ) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(exception);
    }
    exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
    return exchange.getResponse().setComplete();
  }

  private Mono<Void> routeAndRegister(
      final ServerWebExchange exchange,
      final GatewayFilterChain chain
  ) {
    return chain.filter(exchange).then(Mono.defer(() -> {
      String sessionId = normalizeSessionId(
          exchange.getResponse().getHeaders().getFirst(SESSION_HEADER)
      );
      URI selected = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
      String route = routeBase(selected);
      if (sessionId == null || route == null) {
        return Mono.empty();
      }
      return redisTemplate.opsForValue()
          .set(redisKey(sessionId), route, affinityTtl())
          .onErrorReturn(Boolean.FALSE)
          .then();
    }));
  }

  private Mono<Void> deleteQuietly(final String key) {
    return redisTemplate.delete(key)
        .onErrorReturn(0L)
        .then();
  }

  private String redisKey(final String sessionId) {
    String prefix = properties.getKeyPrefix();
    if (prefix == null || prefix.isBlank() || prefix.length() > 200
        || prefix.contains("\r") || prefix.contains("\n")) {
      throw new IllegalStateException("MCP session affinity key prefix is invalid");
    }
    return prefix.trim() + sha256(sessionId);
  }

  private Duration affinityTtl() {
    Duration value = properties.getTtl();
    return value == null || value.isNegative() || value.isZero()
        ? Duration.ofMinutes(10) : value;
  }

  static URI mappedUri(final URI current, final String route) {
    if (current == null || route == null || route.isBlank()) {
      return null;
    }
    try {
      URI target = URI.create(route);
      if (!isHttp(target) || target.getHost() == null) {
        return null;
      }
      return UriComponentsBuilder.fromUri(current)
          .scheme(target.getScheme())
          .host(target.getHost())
          .port(target.getPort())
          .build(true)
          .toUri();
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  static String routeBase(final URI uri) {
    if (!isHttp(uri) || uri.getHost() == null) {
      return null;
    }
    return UriComponentsBuilder.newInstance()
        .scheme(uri.getScheme())
        .host(uri.getHost())
        .port(uri.getPort())
        .build()
        .toUriString();
  }

  private static boolean isHttp(final URI uri) {
    return uri != null && ("http".equalsIgnoreCase(uri.getScheme())
        || "https".equalsIgnoreCase(uri.getScheme()));
  }

  private static boolean isStaleMappingStatus(final HttpStatusCode status) {
    return status != null && (status.value() == 404
        || status.value() == 502
        || status.value() == 503
        || status.value() == 504);
  }

  static String normalizeSessionId(final String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    if (normalized.length() > 256 || normalized.contains("\r")
        || normalized.contains("\n")) {
      return null;
    }
    return normalized;
  }

  private static String sha256(final String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
