/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.gateway.server.config;

import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Routes browser navigation requests for AI workbench pages to the host SPA.
 *
 * <p>The AI workbench UI and its backend APIs intentionally share the
 * {@code /ai/workbench/**} prefix. A path-only route would therefore turn API responses into
 * HTML. This route only handles requests that identify themselves as document navigation;
 * all other requests continue to the AI gateway route.</p>
 */
@Configuration(proxyBeanMethods = false)
public class AiSpaRouteConfig {

  private static final String AI_WORKBENCH_PATH = "/ai/workbench/**";

  private static final String FETCH_DESTINATION_HEADER = "Sec-Fetch-Dest";

  private static final String DOCUMENT_DESTINATION = "document";

  private final Resource indexHtml;

  /**
   * Creates the production route backed by the packaged host entry document.
   */
  public AiSpaRouteConfig() {
    this(new ClassPathResource("static/index.html"));
  }

  AiSpaRouteConfig(final Resource indexHtml) {
    this.indexHtml = indexHtml;
  }

  /**
   * Registers the AI workbench history route before the gateway handler mapping.
   *
   * @return document-navigation-only SPA route
   */
  @Bean
  public RouterFunction<ServerResponse> aiSpaNavigationRoute() {
    RequestPredicate navigationRequest = RequestPredicates.GET(AI_WORKBENCH_PATH)
        .and(AiSpaRouteConfig::isDocumentNavigation);
    return RouterFunctions.route(navigationRequest, request -> ServerResponse.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noStore())
        .body(BodyInserters.fromResource(indexHtml)));
  }

  static boolean isDocumentNavigation(final ServerRequest request) {
    String fetchDestination = request.headers().firstHeader(FETCH_DESTINATION_HEADER);
    if (fetchDestination != null
        && DOCUMENT_DESTINATION.equals(fetchDestination.trim().toLowerCase(Locale.ROOT))) {
      return true;
    }

    try {
      return request.headers().accept().stream()
          .filter(mediaType -> mediaType.getQualityValue() > 0)
          .filter(mediaType -> !mediaType.isWildcardType() && !mediaType.isWildcardSubtype())
          .anyMatch(MediaType.TEXT_HTML::isCompatibleWith);
    } catch (InvalidMediaTypeException ignored) {
      return false;
    }
  }
}
