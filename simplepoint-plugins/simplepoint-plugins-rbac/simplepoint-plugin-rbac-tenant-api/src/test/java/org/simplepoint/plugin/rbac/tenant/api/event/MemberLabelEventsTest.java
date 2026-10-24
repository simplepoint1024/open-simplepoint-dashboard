package org.simplepoint.plugin.rbac.tenant.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.plugin.rbac.tenant.api.vo.MemberLabelDirectoryModels.Revision;

class MemberLabelEventsTest {
  @Test
  void preservesFullTenantIdentityAndExactRevisionWithStableScopedOperation() {
    var revision = new Revision(UUID.randomUUID().toString(), Long.MAX_VALUE);
    var tenant = "🦊".repeat(255);
    var first = MemberLabelEvents.observed(tenant, revision, Instant.now());
    var second = MemberLabelEvents.observed(tenant, revision, Instant.now());
    assertEquals(revision, MemberLabelEvents.revision(first));
    assertEquals(tenant, first.tenantId());
    assertEquals(first.operationId(), second.operationId());
    assertNotEquals(first.eventId(), second.eventId());
    assertNotEquals(
        first.operationId(),
        MemberLabelEvents.observed("other", revision, Instant.now()).operationId());
    assertThrows(
        IllegalArgumentException.class,
        () -> MemberLabelEvents.observed("🦊".repeat(256), revision, Instant.now()));
  }

  @Test
  void rejectsProducerScopeAndExtraPayloadBeforeConsumerEffects() {
    var event =
        MemberLabelEvents.observed(
            "tenant", new Revision(UUID.randomUUID().toString(), 1), Instant.now());
    for (var changed :
        java.util.List.of(
            copy(event, "workflow", "tenant", event.resourceId(), event.payload()),
            copy(event, event.producer(), "other", event.resourceId(), event.payload()),
            copy(event, event.producer(), "tenant", "other", event.payload()),
            copy(
                event,
                event.producer(),
                "tenant",
                event.resourceId(),
                JsonNodeFactory.instance.objectNode().put("epoch", "invalid")),
            copy(
                event,
                event.producer(),
                "tenant",
                event.resourceId(),
                ((com.fasterxml.jackson.databind.node.ObjectNode) event.payload())
                    .put("credentials", "forbidden")))) {
      assertThrows(IllegalArgumentException.class, () -> MemberLabelEvents.revision(changed));
    }
  }

  private static EventMessage copy(
      EventMessage event,
      String producer,
      String tenant,
      String resource,
      com.fasterxml.jackson.databind.JsonNode payload) {
    return new EventMessage(
        event.eventId(),
        event.eventType(),
        event.schemaVersion(),
        producer,
        tenant,
        resource,
        event.generation(),
        event.operationId(),
        event.correlationId(),
        event.occurredAt(),
        payload);
  }
}
