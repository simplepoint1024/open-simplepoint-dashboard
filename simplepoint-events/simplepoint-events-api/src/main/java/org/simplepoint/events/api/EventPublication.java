package org.simplepoint.events.api;

/** Versioned publication capability without payloads or transport credentials. */
public record EventPublication(String eventType, int schemaVersion) {
  /** Validates the same event identity rules used by the wire envelope. */
  public EventPublication {
    EventMessage.token(eventType, "event type");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("Event schema version must be positive");
    }
  }

  /** Checks the event type and version independently of producer authorization. */
  public boolean accepts(EventMessage event) {
    return eventType.equals(event.eventType()) && schemaVersion == event.schemaVersion();
  }
}
