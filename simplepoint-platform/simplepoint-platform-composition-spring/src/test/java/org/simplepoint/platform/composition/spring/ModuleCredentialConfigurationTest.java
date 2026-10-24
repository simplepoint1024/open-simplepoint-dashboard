package org.simplepoint.platform.composition.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.simplepoint.service.router.config.ServiceRouterProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ModuleCredentialConfigurationTest {

  @Test
  void cachesAndRefreshesEachModulesOwnOauthCredentialWithoutUsingTheHostCredential() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    expectToken(server, "simplepoint-module-caller-one", "one-secret", "one-token");
    expectToken(server, "simplepoint-module-caller-two", "two-secret", "two-token");
    expectToken(server, "simplepoint-module-caller-one", "one-secret", "fresh-one-token");
    var credentials = create(builder.build(), environment());
    try (var ignored = credentials.bind("caller-one")) {
      assertThat(credentials.getAccessToken()).isEqualTo("one-token");
      assertThat(credentials.getAccessToken()).isEqualTo("one-token");
    }
    try (var ignored = credentials.bind("caller-two")) {
      assertThat(credentials.getAccessToken()).isEqualTo("two-token");
    }
    try (var ignored = credentials.bind("caller-one")) {
      credentials.invalidate("two-token");
      assertThat(credentials.getAccessToken()).isEqualTo("one-token");
      credentials.invalidate("one-token");
      assertThat(credentials.getAccessToken()).isEqualTo("fresh-one-token");
      try (var nested = credentials.bind("caller-two")) {
        assertThat(credentials.getAccessToken()).isEqualTo("two-token");
      }
      assertThat(credentials.getAccessToken()).isEqualTo("fresh-one-token");
    }
    assertThatThrownBy(credentials::getAccessToken)
        .isInstanceOf(ModuleCompositionException.class)
        .hasMessageContaining("UNBOUND_MODULE_CALLER");
    server.verify();
  }

  @Test
  void rejectsMissingCredentialsAndSharedClientIdsWithoutExposingSecrets() {
    var environment = environment();
    environment.setProperty("simplepoint.modules.credentials.caller-one.client-secret", " ");
    assertThatThrownBy(() -> create(RestClient.create(), environment))
        .isInstanceOf(ModuleCompositionException.class)
        .hasMessageContaining("MISSING_MODULE_CREDENTIALS")
        .hasMessageNotContaining("two-secret");
    environment.setProperty(
        "simplepoint.modules.credentials.caller-one.client-secret", "one-secret");
    environment.setProperty("simplepoint.modules.credentials.caller-one.client-id", "shared");
    environment.setProperty("simplepoint.modules.credentials.caller-two.client-id", "shared");
    assertThatThrownBy(() -> create(RestClient.create(), environment))
        .isInstanceOf(ModuleCompositionException.class)
        .hasMessageContaining("SHARED_MODULE_CLIENT_ID")
        .hasMessageNotContaining("one-secret")
        .hasMessageNotContaining("two-secret");
  }

  @Test
  void onlySelectedModulesCanBeBoundAndUnpreparedContextCannotBorrowCallerState() {
    var credentials =
        create(
            RestClient.create(),
            environment()
                .withProperty(
                    "simplepoint.modules.credentials.unselected.client-secret", "unused"));
    assertThatThrownBy(() -> credentials.bind("unselected"))
        .isInstanceOf(ModuleCompositionException.class);
    assertThatThrownBy(credentials::prepare).isInstanceOf(ModuleCompositionException.class);
    assertThatThrownBy(credentials::open).isInstanceOf(IllegalStateException.class);
  }

  private ModuleServiceCredentials create(RestClient http, MockEnvironment environment) {
    final var plan =
        ModuleCatalog.discover(getClass().getClassLoader())
            .plan(List.of("caller-one", "caller-two", "callee"));
    var properties = new ServiceRouterProperties();
    properties.getInternalAuth().setMode("oauth2");
    properties.getInternalAuth().getOauth2().setTokenUri("https://issuer.example/token");
    properties.getInternalAuth().getOauth2().setClientId("host-must-not-be-used");
    properties.getInternalAuth().getOauth2().setClientSecret("host-must-not-be-used");
    return new ModuleCredentialConfiguration()
        .moduleServiceCredentials(plan, properties, http, environment);
  }

  private MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty("simplepoint.modules.credentials.caller-one.client-secret", "one-secret")
        .withProperty("simplepoint.modules.credentials.caller-two.client-secret", "two-secret");
  }

  private void expectToken(
      MockRestServiceServer server, String client, String secret, String token) {
    server
        .expect(requestTo("https://issuer.example/token"))
        .andExpect(
            header(
                HttpHeaders.AUTHORIZATION,
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString((client + ":" + secret).getBytes(StandardCharsets.UTF_8))))
        .andRespond(
            withSuccess(
                "{\"access_token\":\"" + token + "\",\"expires_in\":300}",
                MediaType.APPLICATION_JSON));
  }
}
