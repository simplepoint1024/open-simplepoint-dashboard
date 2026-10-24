package org.simplepoint.installation.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.simplepoint.events.composition.ModuleEventPlan;
import org.simplepoint.events.rabbit.EventInfrastructureConfiguration;
import org.simplepoint.events.rabbit.RabbitEventTransport;
import org.simplepoint.platform.installation.InstallationAction;
import org.simplepoint.platform.installation.InstallationFingerprint;

/** Trusted broker-management adapter, separate from all business producers and consumers. */
final class BrokerInstallation {
  private final ObjectMapper json = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final URI base;
  private final String authorization;
  private final String vhost;
  private final Properties properties;

  BrokerInstallation(Properties properties) {
    this.properties = properties;
    base = URI.create(InstallationMain.required(properties, "installation.broker.management-uri"));
    if (base.getHost() == null || base.getPort() == 0 || base.getPort() > 65535
        || (!base.getPath().isEmpty() && !"/".equals(base.getPath()))
        || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
        || (!"https".equals(base.getScheme()) && !("http".equals(base.getScheme())
            && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost())))) {
      throw new IllegalArgumentException("Broker management requires HTTPS or a local loopback endpoint");
    }
    authorization = "Basic " + Base64.getEncoder().encodeToString((
        InstallationMain.required(properties, "installation.broker.username") + ":"
            + InstallationMain.required(properties, "installation.broker.password")).getBytes(StandardCharsets.UTF_8));
    vhost = InstallationMain.required(properties, "spring.rabbitmq.virtual-host");
  }

  String targetFingerprint() {
    return InstallationFingerprint.of(base.toString(), vhost);
  }

  Map<String, Map<String, String>> permissionSnapshot(ModuleEventPlan plan) throws Exception {
    var result = new java.util.TreeMap<String, Map<String, String>>();
    for (var channel : plan.channels().values()) {
      result.put(channel.identity(), permissionValues(get(permissionPath(channel.identity()))));
    }
    return Map.copyOf(result);
  }

  /** Preflights the complete desired topology and reviewed ACL changes before returning any writable action. */
  List<InstallationAction> prepare(ModuleEventPlan plan,
      Map<String, Map<String, String>> approvedPermissions) throws Exception {
    require(get("vhosts/" + segment(vhost)).isObject(), "Broker virtual host must be explicitly provisioned");
    require(approvedPermissions.keySet().equals(plan.channels().values().stream()
        .map(ModuleEventPlan.Channel::identity).collect(java.util.stream.Collectors.toSet())),
        "Broker permission review does not cover the selected owners");
    var exchanges = new ArrayList<InstallationAction>();
    var queues = new ArrayList<InstallationAction>();
    var bindings = new ArrayList<InstallationAction>();
    var users = new ArrayList<InstallationAction>();
    var permissions = new ArrayList<InstallationAction>();
    for (var channel : plan.channels().values()) {
      String exchange = RabbitEventTransport.exchange(channel.identity());
      String exchangePath = "exchanges/" + segment(vhost) + "/" + segment(exchange);
      var exchangeBody = Map.of("type", "direct", "durable", true, "auto_delete", false,
          "internal", false, "arguments", Map.of());
      exchanges.add(objectAction("exchange-" + channel.identity(), exchangePath, exchangeBody));
      for (var subscription : channel.subscriptions()) {
        String queue = EventInfrastructureConfiguration.queueName(channel.identity(), subscription.id());
        String queuePath = "queues/" + segment(vhost) + "/" + segment(queue);
        var queueBody = Map.of("durable", true, "auto_delete", false,
            "arguments", Map.of("x-queue-type", "quorum", "x-delivery-limit", -1));
        String suffix = InstallationFingerprint.of(channel.identity(), subscription.id()).substring(0, 24);
        queues.add(objectAction("queue-" + suffix, queuePath, queueBody));
        String producer = RabbitEventTransport.exchange(subscription.producer());
        if (plan.channels().values().stream().noneMatch(value -> value.identity().equals(subscription.producer()))) {
          require(matches(get("exchanges/" + segment(vhost) + "/" + segment(producer)), exchangeBody),
              "External producer exchange is missing or incompatible");
        }
        String path = "bindings/" + segment(vhost) + "/e/" + segment(producer) + "/q/" + segment(queue);
        var body = Map.of("routing_key", RabbitEventTransport.routing(subscription.eventType(), subscription.schemaVersion()),
            "arguments", Map.of());
        bindings.add(action("binding-" + suffix, path, body,
            () -> write("POST", path, body), () -> bindingExists(path, body)));
      }
      String secret = InstallationMain.required(properties, "simplepoint.events.modules." + channel.module() + ".password");
      String userPath = "users/" + segment(channel.identity());
      var userBody = Map.of("password", secret, "tags", "");
      userMatches(userPath, secret);
      users.add(action("user-" + channel.identity(), userPath, userBody, () -> {
        if (!userMatches(userPath, secret)) {
          write("PUT", userPath, userBody);
        }
      }, () -> userMatches(userPath, secret)));
      List<String> ownedQueues = channel.subscriptions().stream()
          .map(subscription -> EventInfrastructureConfiguration.queueName(channel.identity(), subscription.id())).sorted().toList();
      String read = ownedQueues.isEmpty() ? "^$" : "^(?:" + ownedQueues.stream().map(BrokerInstallation::literal)
          .collect(java.util.stream.Collectors.joining("|")) + ")$";
      var desired = Map.of("configure", "^$", "write", "^" + literal(exchange) + "$", "read", read);
      String path = permissionPath(channel.identity());
      var previous = approvedPermissions.get(channel.identity());
      require(previous.isEmpty() || previous.keySet().equals(desired.keySet()), "Invalid broker permission review");
      requireAllowedPermissionState(path, previous, desired);
      permissions.add(action("permissions-" + channel.identity(), path, Map.of("previous", previous, "desired", desired),
          () -> {
            requireAllowedPermissionState(path, previous, desired);
            if (!permissionValues(get(path)).equals(desired)) {
              write("PUT", path, desired);
            }
          }, () -> {
            requireAllowedPermissionState(path, previous, desired);
            return permissionValues(get(path)).equals(desired);
          }));
    }
    var actions = new ArrayList<InstallationAction>();
    actions.addAll(exchanges);
    actions.addAll(queues);
    actions.addAll(bindings);
    actions.addAll(users);
    actions.addAll(permissions);
    // Also validate binding responses before permitting the first topology mutation.
    for (var action : actions) {
      action.completedReceipt();
    }
    return List.copyOf(actions);
  }

  private InstallationAction objectAction(String id, String path, Map<String, ?> body) throws Exception {
    var existing = get(path);
    require(existing.isMissingNode() || matches(existing, body), "Broker topology configuration conflict");
    return action(id, path, body, () -> {
      var current = get(path);
      require(current.isMissingNode() || matches(current, body), "Broker topology configuration conflict");
      if (current.isMissingNode()) {
        write("PUT", path, body);
      }
    }, () -> {
      var current = get(path);
      require(current.isMissingNode() || matches(current, body), "Broker topology configuration conflict");
      return !current.isMissingNode();
    });
  }

  private InstallationAction action(String id, String path, Object inputs,
      CheckedWrite write, CheckedRead read) throws Exception {
    String fingerprint = InstallationFingerprint.of(targetFingerprint(), path, json.writeValueAsString(inputs));
    return new VerifiedInstallationAction(id, fingerprint, () -> {
      try {
        write.apply();
      } catch (Exception failure) {
        throw new IllegalStateException("Broker installation step failed: " + id, failure);
      }
    }, () -> {
      try {
        return read.complete();
      } catch (Exception failure) {
        throw new IllegalStateException("Broker installation reconciliation failed: " + id, failure);
      }
    });
  }

  private boolean matches(JsonNode actual, Map<String, ?> expected) {
    return actual.isObject() && expected.entrySet().stream()
        .allMatch(entry -> actual.path(entry.getKey()).equals(json.valueToTree(entry.getValue())));
  }

  private boolean bindingExists(String path, Map<String, ?> body) throws Exception {
    var existing = get(path);
    if (existing.isMissingNode()) {
      return false;
    }
    require(existing.isArray(), "Invalid broker binding response");
    for (var binding : existing) {
      if (matches(binding, body)) {
        return true;
      }
    }
    return false;
  }

  private boolean userMatches(String path, String secret) throws Exception {
    var user = get(path);
    if (user.isMissingNode()) {
      return false;
    }
    require(user.path("tags").isArray() && user.path("tags").isEmpty(), "Broker runtime identity has privileged tags");
    require(passwordMatches(user, secret), "Broker runtime credential conflicts with installation");
    return true;
  }

  private void requireAllowedPermissionState(String path, Map<String, String> previous,
      Map<String, String> desired) throws Exception {
    var current = permissionValues(get(path));
    require(current.equals(previous) || current.equals(desired), "Broker permissions changed since review");
  }

  private Map<String, String> permissionValues(JsonNode value) {
    if (value.isMissingNode()) {
      return Map.of();
    }
    require(value.isObject(), "Invalid broker permission response");
    var result = new java.util.TreeMap<String, String>();
    for (String name : List.of("configure", "write", "read")) {
      require(value.path(name).isTextual() && value.path(name).asText().length() <= 4096,
          "Invalid broker permission value");
      result.put(name, value.path(name).asText());
    }
    return Map.copyOf(result);
  }

  private String permissionPath(String identity) {
    return "permissions/" + segment(vhost) + "/" + segment(identity);
  }

  @FunctionalInterface
  private interface CheckedWrite {
    void apply() throws Exception;
  }

  @FunctionalInterface
  private interface CheckedRead {
    boolean complete() throws Exception;
  }

  private boolean passwordMatches(JsonNode user, String secret) throws Exception {
    if (!"rabbit_password_hashing_sha256".equals(user.path("hashing_algorithm").asText())) {
      return false;
    }
    byte[] encoded = Base64.getDecoder().decode(user.path("password_hash").asText());
    if (encoded.length != 36) {
      return false;
    }
    var digest = MessageDigest.getInstance("SHA-256");
    digest.update(encoded, 0, 4);
    byte[] expected = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
    return MessageDigest.isEqual(expected, java.util.Arrays.copyOfRange(encoded, 4, encoded.length));
  }

  private JsonNode get(String path) throws Exception {
    var response = http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var body = response.body()) {
      if (response.statusCode() == 404) {
        return json.missingNode();
      }
      require(response.statusCode() == 200, "Broker read failed");
      byte[] bytes = body.readNBytes(1048577);
      require(bytes.length <= 1048576, "Broker response exceeds limit");
      return json.readTree(bytes);
    }
  }

  private void write(String method, String path, Object body) throws Exception {
    var response = http.send(request(path).header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build(),
        HttpResponse.BodyHandlers.discarding());
    require(response.statusCode() >= 200 && response.statusCode() < 300, "Broker provisioning failed");
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(base.resolve("/api/" + path)).timeout(Duration.ofSeconds(10)).header("Authorization", authorization);
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String literal(String value) {
    return value.replace(".", "\\.");
  }

  private static void require(boolean valid, String message) {
    if (!valid) {
      throw new IllegalStateException(message);
    }
  }
}
