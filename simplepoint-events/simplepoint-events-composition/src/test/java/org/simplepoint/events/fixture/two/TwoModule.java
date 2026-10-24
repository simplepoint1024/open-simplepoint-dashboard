package org.simplepoint.events.fixture.two;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.events.composition.FixturePublisher;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual Spring factory ownership fixture. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "two", packages = "org.simplepoint.events.fixture.two")
@ModuleEvents(TwoModule.Manifest.class)
public class TwoModule {
  public static final EventSubscription SUBSCRIPTION =
      new EventSubscription("two.receive", "legacy-one", "fixture.one", 1);

  /** Creates a publisher bound to this factory module. */
  @Bean
  FixturePublisher twoPublisher(EventPublisher publisher) {
    return new FixturePublisher(publisher, "legacy-two", "fixture.two");
  }

  /** Creates the local durable effect. */
  @Bean
  EventHandler twoHandler(JdbcTemplate jdbc, EventPublisher publisher) {
    return new EventHandler() {
      @Override
      public EventSubscription subscription() {
        return SUBSCRIPTION;
      }

      @Override
      public void handle(EventMessage event) {
        jdbc.update(
            "insert into effects (owner, event_id) values (?, ?)",
            "two",
            event.eventId().toString());
        publisher.publish(FixturePublisher.event("legacy-two", "fixture.two"));
      }
    };
  }

  /** Pure declaration shared with the runtime handler. */
  public static final class Manifest implements EventDeclarations {
    @Override
    public String identity() {
      return "legacy-two";
    }

    @Override
    public List<EventPublication> publications() {
      return List.of(new EventPublication("fixture.two", 1));
    }

    @Override
    public List<EventSubscription> subscriptions() {
      return List.of(SUBSCRIPTION);
    }
  }
}
