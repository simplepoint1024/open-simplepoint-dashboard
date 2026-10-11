package org.simplepoint.gateway.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Serves the host SPA for workflow-center browser navigation. */
@Configuration(proxyBeanMethods = false)
public class WorkflowSpaRouteConfig {

  private final Resource indexHtml;

  /** Creates the production route using the packaged host entry document. */
  public WorkflowSpaRouteConfig() {
    this(new ClassPathResource("static/index.html"));
  }

  WorkflowSpaRouteConfig(final Resource indexHtml) {
    this.indexHtml = indexHtml;
  }

  /** Leaves workflow APIs and federated assets to the gateway route. */
  @Bean
  public RouterFunction<ServerResponse> workflowSpaNavigationRoute() {
    return RouterFunctions.route(
        RequestPredicates.GET("/workflow/**")
            .and(request -> !request.path().startsWith("/workflow/mf/"))
            .and(SpaNavigationRequest::isDocumentNavigation),
        request -> ServerResponse.ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .body(BodyInserters.fromResource(indexHtml))
    );
  }
}
