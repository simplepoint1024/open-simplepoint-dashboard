package org.simplepoint.gateway.server.config;

import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Browser APIs use the gateway session; delegated issuer and machine APIs enforce their own
 * security.
 */
public final class BrowserCsrfMatcher implements ServerWebExchangeMatcher {
  @Override
  public Mono<MatchResult> matches(ServerWebExchange exchange) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    // The issuer requires its own synchronizer token on login, account management and consent.
    // MCP and AI compatible APIs require explicit tokens and do not authenticate via gateway
    // cookies.
    if (path.startsWith("/authorization/")
        || path.startsWith("/mcp/")
        || path.equals("/mcp")
        || path.startsWith("/ai/v1/")) {
      return MatchResult.notMatch();
    }
    return CsrfWebFilter.DEFAULT_CSRF_MATCHER.matches(exchange);
  }
}
