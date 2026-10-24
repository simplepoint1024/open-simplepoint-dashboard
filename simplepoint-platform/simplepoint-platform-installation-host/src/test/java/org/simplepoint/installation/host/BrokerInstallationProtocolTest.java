package org.simplepoint.installation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.composition.ModuleEventPlan;

/** Stateful management API fixture: real HTTP, credential hashes, effects and lost acknowledgements. */
class BrokerInstallationProtocolTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ModuleEventPlan PLAN = new ModuleEventPlan(Map.of("owner", new ModuleEventPlan.Channel(
      "owner", "owner", List.of(new EventPublication("changed", 1)),
      List.of(new EventSubscription("projection", "owner", "changed", 1)))));

  @Test
  void preparationHasNoEffectsAndRestartReconcilesTheCompleteTopology() throws Exception {
    try (var broker = new Broker()) {
      var installer = broker.installer();
      var review = installer.permissionSnapshot(PLAN);
      var actions = installer.prepare(PLAN, review);
      assertThat(broker.writes).isEmpty();
      for (var action : actions) {
        action.apply();
      }
      assertThat(actions).hasSize(5);
      assertThat(broker.writes).hasSize(5);
      assertThat(broker.writes.getLast()).contains("permissions");
      var repeated = broker.installer().prepare(PLAN, review);
      assertThat(repeated.stream().map(action -> action.definition()).toList())
          .isEqualTo(actions.stream().map(action -> action.definition()).toList());
      for (var action : repeated) {
        assertThat(action.completedReceipt()).isPresent();
      }
      assertThat(broker.writes).hasSize(5);
    }
  }

  @Test
  void lateCredentialConflictPreventsEvenAnEarlierExchangeCreation() throws Exception {
    try (var broker = new Broker()) {
      broker.entries.put("/api/users/owner", broker.user("different"));
      var installer = broker.installer();
      var review = installer.permissionSnapshot(PLAN);
      assertThatThrownBy(() -> installer.prepare(PLAN, review)).isInstanceOf(IllegalStateException.class);
      assertThat(broker.writes).isEmpty();
    }
  }

  @Test
  void reviewedPermissionTransitionIsExplicitAndUnreviewedDriftStopsBeforeMutation() throws Exception {
    try (var broker = new Broker()) {
      String path = "/api/permissions//fixture/owner";
      broker.entries.put(path, JSON.valueToTree(Map.of("configure", "^$", "write", "^legacy$", "read", "^owner.*$")));
      var installer = broker.installer();
      var review = installer.permissionSnapshot(PLAN);
      var actions = installer.prepare(PLAN, review);
      assertThat(broker.writes).isEmpty();
      for (var action : actions) {
        action.apply();
      }
      var desired = broker.entries.get(path);
      assertThat(desired.path("configure").asText()).isEqualTo("^$");
      assertThat(desired.path("read").asText()).doesNotContain(".*");
      broker.entries.put(path, JSON.valueToTree(Map.of("configure", ".*", "write", ".*", "read", ".*")));
      int before = broker.writes.size();
      assertThatThrownBy(() -> installer.prepare(PLAN, review)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> actions.getLast().apply()).isInstanceOf(IllegalStateException.class);
      assertThat(broker.writes).hasSize(before);
    }
  }

  @Test
  void lostWriteAcknowledgementRetainsReadableCommittedReceipt() throws Exception {
    try (var broker = new Broker()) {
      var installer = broker.installer();
      var actions = installer.prepare(PLAN, installer.permissionSnapshot(PLAN));
      broker.loseNextAcknowledgement = true;
      assertThatThrownBy(() -> actions.getFirst().apply()).isInstanceOf(IllegalStateException.class);
      assertThat(actions.getFirst().completedReceipt()).isPresent();
      assertThat(broker.writes).hasSize(1);
    }
  }

  private static final class Broker implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, JsonNode> entries = new ConcurrentHashMap<>();
    private final List<String> writes = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile boolean loseNextAcknowledgement;

    Broker() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      entries.put("/api/vhosts//fixture", JSON.createObjectNode());
      server.createContext("/", exchange -> {
        try (exchange) {
          String path = exchange.getRequestURI().getPath();
          if ("GET".equals(exchange.getRequestMethod())) {
            JsonNode value = entries.get(path);
            byte[] bytes = JSON.writeValueAsBytes(value);
            exchange.sendResponseHeaders(value == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } else {
            writes.add(path);
            JsonNode input = JSON.readTree(exchange.getRequestBody());
            if (path.startsWith("/api/users/")) {
              input = user(input.path("password").asText());
            } else if (path.startsWith("/api/bindings/")) {
              input = JSON.createArrayNode().add(input);
            }
            entries.put(path, input);
            int status = loseNextAcknowledgement ? 500 : 204;
            loseNextAcknowledgement = false;
            exchange.sendResponseHeaders(status, -1);
          }
        }
      });
      server.start();
    }

    JsonNode user(String secret) {
      try {
        byte[] salt = new byte[] {1, 2, 3, 4};
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        byte[] encoded = new byte[36];
        System.arraycopy(salt, 0, encoded, 0, 4);
        System.arraycopy(hash, 0, encoded, 4, hash.length);
        return JSON.valueToTree(Map.of("password_hash", Base64.getEncoder().encodeToString(encoded),
            "hashing_algorithm", "rabbit_password_hashing_sha256", "tags", List.of()));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    BrokerInstallation installer() {
      var properties = new Properties();
      properties.setProperty("installation.broker.management-uri", "http://127.0.0.1:" + server.getAddress().getPort());
      properties.setProperty("installation.broker.username", "fixture-installer");
      properties.setProperty("installation.broker.password", "fixture-only");
      properties.setProperty("spring.rabbitmq.virtual-host", "/fixture");
      properties.setProperty("simplepoint.events.modules.owner.password", "runtime-fixture-only");
      return new BrokerInstallation(properties);
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
