package org.simplepoint.gateway.server.config;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.gateway.server.controller.CsrfController;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class BrowserCsrfHttpTest {
  WebTestClient client;
  @BeforeEach void setup() {
    CsrfWebFilter filter = new CsrfWebFilter();
    filter.setRequireCsrfProtectionMatcher(new BrowserCsrfMatcher());
    client = WebTestClient.bindToController(new CsrfController(), new Writes()).webFilter(filter).build();
  }
  @Test void cookieAloneCannotWriteAndWrongOrOtherSessionTokenIsRejected() {
    var result = client.get().uri("/csrf").exchange().expectStatus().isOk()
        .expectHeader().valueEquals("Cache-Control", "no-store").expectBody(Map.class).returnResult();
    String session = result.getResponseCookies().getFirst("SESSION").getValue();
    String token = (String) result.getResponseBody().get("token");
    client.post().uri("/common/items").cookie("SESSION", session).exchange().expectStatus().isForbidden();
    client.post().uri("/common/items").cookie("SESSION", session).header("X-CSRF-TOKEN", "wrong")
        .exchange().expectStatus().isForbidden();
    client.post().uri("/common/items").header("X-CSRF-TOKEN", token).exchange().expectStatus().isForbidden();
    client.post().uri("/common/items").cookie("SESSION", session).header("X-CSRF-TOKEN", token)
        .exchange().expectStatus().isOk();
    client.post().uri("/common/upload").cookie("SESSION", session).header("X-CSRF-TOKEN", token)
        .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA).bodyValue("file-body")
        .exchange().expectStatus().isOk();
  }
  @Test void logoutFormIsProtectedAndDelegatedMachineProtocolsAreNotBroken() {
    var result = client.get().uri("/csrf").exchange().expectBody(Map.class).returnResult();
    client.post().uri("/logout").exchange().expectStatus().isForbidden();
    client.post().uri("/logout").cookie("SESSION", result.getResponseCookies().getFirst("SESSION").getValue())
        .body(org.springframework.web.reactive.function.BodyInserters.fromFormData("_csrf", (String) result.getResponseBody().get("token")))
        .exchange().expectStatus().isOk();
    for (String path : new String[]{"/mcp/demo", "/ai/v1/chat/completions", "/authorization/oauth2/token"}) {
      client.post().uri(path).exchange().expectStatus().isOk();
    }
    // Prefix lookalikes must not gain the delegated exemption.
    client.post().uri("/mcp-admin/delete").exchange().expectStatus().isForbidden();
  }
  @RestController static class Writes {
    @PostMapping({"/common/items", "/common/upload", "/logout", "/mcp/demo", "/ai/v1/chat/completions", "/authorization/oauth2/token"})
    public Map<String, Boolean> write() { return Map.of("ok", true); }
  }
}
