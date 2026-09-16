/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.gateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

class AiSpaRouteConfigTest {

  private static final String INDEX_HTML = "<html><body>SimplePoint host</body></html>";

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    RouterFunction<ServerResponse> spaRoute = new AiSpaRouteConfig(
        new ByteArrayResource(INDEX_HTML.getBytes(StandardCharsets.UTF_8))
    ).aiSpaNavigationRoute();
    RouterFunction<ServerResponse> simulatedGateway = RouterFunctions.route(
        RequestPredicates.all(),
        request -> ServerResponse.status(HttpStatus.BAD_GATEWAY)
            .header("X-Simulated-Handler", "ai-gateway")
            .build()
    );
    client = WebTestClient.bindToRouterFunction(spaRoute.andOther(simulatedGateway)).build();
  }

  @Test
  void forwardsExplicitHtmlNavigationToHostIndex() {
    client.get()
        .uri("/ai/workbench/skills?skillId=example")
        .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
        .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL,
            CacheControl.noStore().getHeaderValue())
        .expectBody(String.class).value(body -> assertThat(body).isEqualTo(INDEX_HTML));
  }

  @Test
  void forwardsFetchDocumentNavigationEvenWhenAcceptDoesNotContainHtml() {
    client.get()
        .uri("/ai/workbench/agents")
        .header("Sec-Fetch-Dest", "Document")
        .accept(MediaType.APPLICATION_JSON)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML);
  }

  @Test
  void letsJsonApiRequestFallThroughToGateway() {
    client.get()
        .uri("/ai/workbench/skills")
        .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
        .expectHeader().valueEquals("X-Simulated-Handler", "ai-gateway");
  }

  @Test
  void doesNotTreatWildcardOrRejectedHtmlAsDocumentNavigation() {
    client.get()
        .uri("/ai/workbench/workflows")
        .header(HttpHeaders.ACCEPT, "text/*")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

    client.get()
        .uri("/ai/workbench/workflows")
        .header(HttpHeaders.ACCEPT, "text/html;q=0,application/json")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
  }

  @Test
  void leavesRemoteAssetsAndNonGetRequestsToExistingHandlers() {
    client.get()
        .uri("/ai/mf/remoteEntry.js")
        .header("Sec-Fetch-Dest", "document")
        .accept(MediaType.TEXT_HTML)
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

    client.post()
        .uri("/ai/workbench/skills")
        .header("Sec-Fetch-Dest", "document")
        .accept(MediaType.TEXT_HTML)
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
  }
}
