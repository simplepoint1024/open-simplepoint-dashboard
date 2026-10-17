package org.simplepoint.platform.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;

/** Versioned resource lifecycle facts shared by independent authorization and data participants. */
public final class ResourceLifecycleEvents {
  public static final String OFFLINE_REQUESTED = "resource.offline.requested";
  public static final String OFFLINE_COMPLETED = "resource.offline.completed";
  public static final Set<String> REQUIRED_PARTICIPANTS = Set.of("common", "dna");

  private ResourceLifecycleEvents() {}

  /** Strict payload validation; scope and generation remain in the event envelope. */
  public static OfflineRequest request(EventMessage event) {
    if (!OFFLINE_REQUESTED.equals(event.eventType()) || event.schemaVersion() != 1
        || !"app".equals(event.producer()) || event.generation() < 1) {
      throw new IllegalArgumentException("Unsupported resource lifecycle request");
    }
    var payload = event.payload();
    String owner = text(payload, "managedOwner");
    if (!owner.equals(event.producer())) {
      throw new IllegalArgumentException("Resource owner must match trusted producer");
    }
    return new OfflineRequest(owner, text(payload, "managedKey"), text(payload, "resourceCode"));
  }

  /** Emits a participant fact only after its effects have succeeded in the current transaction. */
  public static EventMessage completed(EventMessage request, String participant) {
    request(request);
    if (!REQUIRED_PARTICIPANTS.contains(participant)) {
      throw new IllegalArgumentException("Unknown lifecycle participant");
    }
    return new EventMessage(UUID.randomUUID(), OFFLINE_COMPLETED, 1, participant,
        request.tenantId(), request.resourceId(), request.generation(), request.operationId(),
        request.correlationId(), Instant.now(), JsonNodeFactory.instance.objectNode()
        .put("requestEventId", request.payload().path("rootRequestEventId").asText(request.eventId().toString())).put("participant", participant));
  }

  private static String text(JsonNode payload, String name) {
    JsonNode value = payload.get(name);
    if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > 160) {
      throw new IllegalArgumentException("Invalid lifecycle " + name);
    }
    return value.asText();
  }

  /** Resource-owned lookup keys; never arbitrary table names or executable callbacks. */
  public record OfflineRequest(String managedOwner, String managedKey, String resourceCode) {}
}
