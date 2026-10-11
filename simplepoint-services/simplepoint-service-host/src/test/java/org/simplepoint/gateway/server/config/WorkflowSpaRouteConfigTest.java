package org.simplepoint.gateway.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

class WorkflowSpaRouteConfigTest {

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    var spaRoute = new WorkflowSpaRouteConfig(
        new ByteArrayResource("<html>workflow</html>".getBytes(StandardCharsets.UTF_8))
    ).workflowSpaNavigationRoute();
    var gateway = RouterFunctions.route(RequestPredicates.all(),
        request -> ServerResponse.status(HttpStatus.BAD_GATEWAY).build());
    client = WebTestClient.bindToRouterFunction(spaRoute.andOther(gateway)).build();
  }

  @Test
  void servesDocumentsButLeavesRemoteAssetsAndApisToGateway() {
    client.get().uri("/workflow/tasks").accept(MediaType.TEXT_HTML).exchange()
        .expectStatus().isOk().expectBody(String.class)
        .value(body -> assertThat(body).isEqualTo("<html>workflow</html>"));

    client.get().uri("/workflow/mf/mf-manifest.json")
        .header("Sec-Fetch-Dest", "document").accept(MediaType.TEXT_HTML).exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

    client.get().uri("/workflow/tasks/pending")
        .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*").exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
  }
}
