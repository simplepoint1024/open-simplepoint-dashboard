package org.simplepoint.gateway.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Tokens are masked, session-bound and returned only through same-origin, non-cacheable reads. */
@RestController
public class CsrfController {
  /** Returns the masked synchronizer token with caching disabled. */
  @GetMapping("/csrf")
  public Mono<ResponseEntity<CsrfToken>> csrf(ServerWebExchange exchange) {
    Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
    if (token == null) {
      return Mono.just(ResponseEntity.status(503).build());
    }
    return token.map(
        value ->
            ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .body(value));
  }
}
