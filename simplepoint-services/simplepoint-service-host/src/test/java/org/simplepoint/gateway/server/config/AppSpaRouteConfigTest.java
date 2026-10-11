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

class AppSpaRouteConfigTest {

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    var spaRoute = new AppSpaRouteConfig(
        new ByteArrayResource("<html>app</html>".getBytes(StandardCharsets.UTF_8))
    ).appSpaNavigationRoute();
    var gateway = RouterFunctions.route(RequestPredicates.all(),
        request -> ServerResponse.status(HttpStatus.BAD_GATEWAY).build());
    client = WebTestClient.bindToRouterFunction(spaRoute.andOther(gateway)).build();
  }

  @Test
  void servesPagesButLeavesRemoteAssetsAndApiRequestsToGateway() {
    client.get().uri("/app/workbench").accept(MediaType.TEXT_HTML).exchange()
        .expectStatus().isOk().expectBody(String.class)
        .value(body -> assertThat(body).isEqualTo("<html>app</html>"));

    client.get().uri("/app/mf/mf-manifest.json")
        .header("Sec-Fetch-Dest", "document").accept(MediaType.TEXT_HTML).exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

    client.get().uri("/app/forms")
        .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*").exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
  }
}
