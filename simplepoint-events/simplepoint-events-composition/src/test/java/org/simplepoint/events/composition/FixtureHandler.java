package org.simplepoint.events.composition;

import java.util.function.Consumer;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventSubscription;

/** A shared implementation whose owner can only be determined from its declaring factory. */
public record FixtureHandler(EventSubscription subscription, Consumer<EventMessage> effect)
    implements EventHandler {
  @Override
  public void handle(EventMessage event) {
    effect.accept(event);
  }
}
