package org.simplepoint.plugin.workflow.api.event;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;

/** Explicit request and acknowledgement for deleting one completed workflow and its copies. */
public final class WorkflowHistoryRemovalEvents {
  public static final String REQUESTED = "workflow.history-removal.requested";
  public static final String COMPLETED = "workflow.history-removal.completed";
  public static final EventPublication REQUEST = new EventPublication(REQUESTED, 1);
  public static final EventPublication ACK = new EventPublication(COMPLETED, 1);
  public static final Set<String> PARTICIPANTS = Set.of("app", "personal");

  private WorkflowHistoryRemovalEvents() {}

  public static EventSubscription requestSubscription(String participant) {
    requireParticipant(participant);
    return new EventSubscription("workflow-history-remove-" + participant, "workflow", REQUESTED, 1);
  }

  public static EventSubscription acknowledgementSubscription(String participant) {
    requireParticipant(participant);
    return new EventSubscription("workflow-history-removed-" + participant, participant, COMPLETED, 1);
  }

  public static EventMessage requested(String tenant, String instance, int version,
      UUID operation, UUID batch, String submissionId) {
    var payload = JsonNodeFactory.instance.objectNode();
    if (submissionId != null) payload.put("submissionId", submissionId);
    return new EventMessage(operation, REQUESTED, 1, "workflow", tenant, instance,
        version, operation.toString(), batch.toString(), Instant.now(), payload);
  }

  public static String submissionId(EventMessage event) {
    requireScope(event);
    if (!REQUESTED.equals(event.eventType()) || !"workflow".equals(event.producer())
        || !event.eventId().toString().equals(event.operationId())) {
      throw new IllegalArgumentException("Invalid workflow history removal request");
    }
    var value = event.payload().get("submissionId");
    if (value == null) return null;
    if (!value.isTextual() || !value.asText().matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid workflow submission identity");
    }
    return value.asText();
  }

  public static EventMessage completed(EventMessage request, String participant) {
    submissionId(request);
    requireParticipant(participant);
    return new EventMessage(UUID.randomUUID(), COMPLETED, 1, participant, request.tenantId(),
        request.resourceId(), request.generation(), request.operationId(), request.correlationId(),
        Instant.now(), JsonNodeFactory.instance.objectNode());
  }

  public static void requireAcknowledgement(EventMessage event) {
    requireScope(event);
    requireParticipant(event.producer());
    if (!COMPLETED.equals(event.eventType())) {
      throw new IllegalArgumentException("Invalid workflow history removal acknowledgement");
    }
  }

  private static void requireScope(EventMessage event) {
    if (event.schemaVersion() != 1 || event.generation() <= 0 || event.generation() > Integer.MAX_VALUE
        || event.tenantId().length() > 64 || event.resourceId().length() > 64) {
      throw new IllegalArgumentException("Invalid workflow history removal scope");
    }
    UUID.fromString(event.operationId());
    UUID.fromString(event.correlationId());
  }

  private static void requireParticipant(String participant) {
    if (!PARTICIPANTS.contains(participant)) {
      throw new IllegalArgumentException("Invalid workflow history removal participant");
    }
  }
}
