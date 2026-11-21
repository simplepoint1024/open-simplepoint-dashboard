package org.simplepoint.gateway.server.config;

import java.net.URI;
import java.util.List;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

/** Stable public entry for the legacy microservice host's workspace bootstrap APIs. */
@Configuration(proxyBeanMethods = false)
public class WorkspaceBootstrapRoutes {

  @Bean
  RouteDefinitionLocator workspaceBootstrapRouteDefinitionLocator() {
    // Discovery controls instance availability, not whether the public URL exists. A
    // catalog refresh while common is starting must not remove its route indefinitely.
    // Keep this contribution outside the externally replaced, indexed routes list.
    // Route definitions retain the gateway's SaveSession/TokenRelay default filters.
    return () -> Flux.defer(() -> {
      var route = new RouteDefinition();
      route.setId("common-api");
      route.setOrder(-100);
      route.setUri(URI.create("lb://common"));
      route.setPredicates(List.of(new PredicateDefinition("Path=/common/**")));
      route.setFilters(List.of(new FilterDefinition("StripPrefix=1")));
      return Flux.just(route);
    });
  }
}
