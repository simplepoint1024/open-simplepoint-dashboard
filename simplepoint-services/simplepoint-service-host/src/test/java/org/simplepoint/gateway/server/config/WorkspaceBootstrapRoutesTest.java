package org.simplepoint.gateway.server.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.gateway.config.GatewayLoadBalancerProperties;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.ReactiveLoadBalancerClientFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.cloud.gateway.filter.factory.SaveSessionGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.TokenRelayGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.CachingRouteLocator;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.mock.web.server.MockWebSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import reactor.core.publisher.Mono;

/** Real gateway predicates, cached routes, default filters and load-balancer filter. */
class WorkspaceBootstrapRoutesTest {
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void bootstrapSurvivesLateServiceReadinessAndSubsequentOutageWithoutRouteRefresh() {
    var beans = new DefaultListableBeanFactory();
    var registration = ClientRegistration.withRegistrationId("test").clientId("test")
        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
        .tokenUri("https://identity.test/token").build();
    var token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "test-access-token",
        Instant.now(), Instant.now().plusSeconds(300));
    ReactiveOAuth2AuthorizedClientManager manager = request ->
        Mono.just(new OAuth2AuthorizedClient(registration, request.getPrincipal().getName(), token));
    beans.registerSingleton("manager", manager);
    var properties = new GatewayProperties();
    properties.setDefaultFilters(List.of(new FilterDefinition("SaveSession"), new FilterDefinition("TokenRelay")));
    var config = new ConfigurationService(beans, () -> DefaultConversionService.getSharedInstance(), () -> null);
    var source = new RouteDefinitionRouteLocator(
        new WorkspaceBootstrapRoutes().workspaceBootstrapRouteDefinitionLocator(),
        List.of(new PathRoutePredicateFactory(new WebFluxProperties())),
        List.of(new StripPrefixGatewayFilterFactory(), new SaveSessionGatewayFilterFactory(),
            new TokenRelayGatewayFilterFactory(beans.getBeanProvider(ReactiveOAuth2AuthorizedClientManager.class))),
        properties, config);
    var cached = new CachingRouteLocator(source);
    var routes = cached.getRoutes().collectList().block(TIMEOUT);
    assertThat(routes).hasSize(1);
    var route = routes.getFirst();
    var ready = new AtomicBoolean(false);
    var factory = mock(LoadBalancerClientFactory.class);
    ReactorServiceInstanceLoadBalancer balancer = request -> Mono.fromSupplier(() -> ready.get()
        ? new DefaultResponse(new DefaultServiceInstance("common-test", "common", "127.0.0.1", 7000, false))
        : new EmptyResponse());
    when(factory.getInstance("common", ReactorServiceInstanceLoadBalancer.class)).thenReturn(balancer);
    when(factory.getInstances("common", org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle.class))
        .thenReturn(Map.of());
    when(factory.getProperties("common")).thenReturn(new LoadBalancerProperties());
    var handler = new FilteringWebHandler(List.of(new RouteToRequestUrlFilter(),
        new ReactiveLoadBalancerClientFilter(factory, new GatewayLoadBalancerProperties()),
        (exchange, chain) -> {
          assertThat(exchange.getRequest().getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-access-token");
          assertThat(exchange.getRequest().getHeaders().getFirst("X-Tenant-Id")).isEqualTo("personal");
          assertThat(exchange.getRequest().getHeaders().getFirst("X-Context-Id")).isEqualTo("context");
          return chain.filter(exchange);
        }), false);
    var principal = new OAuth2AuthenticationToken(new DefaultOAuth2User(
        List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "alice"), "sub"), List.of(), "test");

    for (boolean available : new boolean[]{false, true, false, true}) {
      ready.set(available);
      // The same cached route must recover without a Consul catalog heartbeat or gateway restart.
      assertThat(cached.getRoutes().blockFirst(TIMEOUT)).isSameAs(route);
      for (String path : List.of("/common/tenants/current", "/common/tenants/current-roles?tenantId=personal",
          "/common/tenants/authorization-context-id?tenantId=personal", "/common/resources/service-routes",
          "/common/i18n/messages/mapping?locale=zh-CN", "/common/mf/mf-manifest.json")) {
        var session = spy(new MockWebSession());
        var exchange = MockServerWebExchange.builder(MockServerHttpRequest.get("https://home.test" + path)
            .header("X-Tenant-Id", "personal").header("X-Context-Id", "context")).session(session).build();
        assertThat(Mono.from(route.getPredicate().apply(exchange)).block(TIMEOUT)).isTrue();
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, route);
        var authenticated = exchange.mutate().principal(Mono.just(principal)).build();
        if (!available) {
          assertThatThrownBy(() -> handler.handle(authenticated).block(TIMEOUT))
              .isInstanceOfSatisfying(NotFoundException.class,
                  failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        } else {
          handler.handle(authenticated).block(TIMEOUT);
          URI forwarded = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);
          assertThat(forwarded).isEqualTo(URI.create("http://127.0.0.1:7000" + path.substring("/common".length())));
          var response = exchange.getRequiredAttribute(GATEWAY_LOADBALANCER_RESPONSE_ATTR);
          assertThat(response).isNotNull();
        }
        verify(session).save();
      }
    }
    for (String path : List.of("/commons/tenants/current", "/userinfo", "/app/runtime", "/internal/test")) {
      assertThat(Mono.from(route.getPredicate().apply(MockServerWebExchange.from(MockServerHttpRequest.get(path))))
          .block(TIMEOUT)).isFalse();
    }
  }
}
