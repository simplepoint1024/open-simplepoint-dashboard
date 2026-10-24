package org.simplepoint.events.composition;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;

/** Shared implementation proves ownership comes from the requesting module's factory. */
public record FixturePublisher(EventPublisher publisher, String producer, String type) {
  /** Produces a bounded test fact without transport configuration. */
  public void publish() {
    publisher.publish(event(producer, type));
  }

  /** Creates independent event identities for transaction and delivery assertions. */
  public static EventMessage event(String producer, String type) {
    var id = UUID.randomUUID();
    return new EventMessage(
        id,
        type,
        1,
        producer,
        "tenant",
        "resource",
        1,
        id.toString(),
        id.toString(),
        Instant.now(),
        JsonNodeFactory.instance.objectNode());
  }
}
