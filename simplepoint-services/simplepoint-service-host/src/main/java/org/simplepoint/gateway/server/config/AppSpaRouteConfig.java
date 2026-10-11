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

/** Serves the host SPA for application workbench document navigation. */
@Configuration(proxyBeanMethods = false)
public class AppSpaRouteConfig {

  private final Resource indexHtml;

  /** Creates the production route using the packaged host entry document. */
  public AppSpaRouteConfig() {
    this(new ClassPathResource("static/index.html"));
  }

  AppSpaRouteConfig(Resource indexHtml) {
    this.indexHtml = indexHtml;
  }

  /** Leaves remote assets and future JSON APIs to the application service. */
  @Bean
  public RouterFunction<ServerResponse> appSpaNavigationRoute() {
    return RouterFunctions.route(
        RequestPredicates.GET("/app/**")
            .and(request -> !request.path().startsWith("/app/mf/"))
            .and(SpaNavigationRequest::isDocumentNavigation),
        request -> ServerResponse.ok()
            .contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .body(BodyInserters.fromResource(indexHtml))
    );
  }
}
