package org.simplepoint.plugin.ai.skill.service.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.ai.skill.api.model.SkillManagedRegistryStatus;

class ManagedOciSkillRegistryServiceTest {

  private HttpServer registry;

  private String registryHost;

  @BeforeEach
  void setUp() throws IOException {
    registry = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registryHost = "127.0.0.1:" + registry.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    registry.stop(0);
  }

  @Test
  void describesDisabledConfigurationWithoutAttemptingNetworkAccess() {
    SkillArtifactProperties properties = new SkillArtifactProperties();
    ManagedOciSkillRegistryService service = service(properties);

    SkillManagedRegistryStatus status = service.describe();

    assertThat(status.configured()).isFalse();
    assertThat(status.connected()).isNull();
    assertThat(status.message()).isEqualTo("SKILL_REGISTRY_NOT_CONFIGURED");
  }

  @Test
  void checksAnAuthenticatedRegistryWithoutExposingCredentials() {
    String expectedAuthorization = "Basic " + Base64.getEncoder()
        .encodeToString("publisher:secret".getBytes(StandardCharsets.UTF_8));
    registry.createContext("/v2/", exchange -> {
      String authorization = exchange.getRequestHeaders()
          .getFirst("Authorization");
      if (!expectedAuthorization.equals(authorization)) {
        exchange.getResponseHeaders().set(
            "WWW-Authenticate",
            "Basic realm=\"managed-registry\""
        );
        respond(exchange, 401);
        return;
      }
      respond(exchange, 200);
    });
    registry.start();
    SkillArtifactProperties properties = configuredProperties();
    properties.setRegistryUsername("publisher");
    properties.setRegistryPassword("secret");

    SkillManagedRegistryStatus status = service(properties)
        .checkConnectivity();

    assertThat(status.connected()).isTrue();
    assertThat(status.registry()).isEqualTo(registryHost);
    assertThat(status.repositoryPrefix()).isEqualTo("simplepoint/skills");
    assertThat(status.secureTransport()).isFalse();
    assertThat(status.authenticationConfigured()).isTrue();
    assertThat(status.toString()).doesNotContain("secret");
  }

  @Test
  void rejectsManagedRegistryOutsideTheAllowlistBeforeNetworkAccess() {
    SkillArtifactProperties properties = configuredProperties();
    properties.setAllowedRegistries(List.of("registry.example.com"));

    assertThatThrownBy(() -> service(properties).checkConnectivity())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("allowed Registry list");
  }

  private SkillArtifactProperties configuredProperties() {
    SkillArtifactProperties properties = new SkillArtifactProperties();
    properties.setManagedRegistryEnabled(true);
    properties.setManagedRegistry(registryHost);
    properties.setManagedRepositoryPrefix("simplepoint/skills");
    properties.setAllowedRegistries(List.of(registryHost));
    properties.setAllowedTokenHosts(List.of(registryHost));
    properties.setInsecureRegistries(List.of(registryHost));
    return properties;
  }

  private ManagedOciSkillRegistryService service(
      final SkillArtifactProperties properties
  ) {
    return new ManagedOciSkillRegistryService(properties, new ObjectMapper());
  }

  private void respond(final HttpExchange exchange, final int status)
      throws IOException {
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }
}
