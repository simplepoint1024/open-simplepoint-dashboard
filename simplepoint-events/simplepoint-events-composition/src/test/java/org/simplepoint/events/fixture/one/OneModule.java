package org.simplepoint.events.fixture.one;

import java.util.List;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventHandler;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.events.composition.FixtureHandler;
import org.simplepoint.events.composition.FixturePublisher;
import org.simplepoint.platform.composition.PlatformModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual Spring factory ownership fixture. */
@Configuration(proxyBeanMethods = false)
@PlatformModule(id = "one", packages = "org.simplepoint.events.fixture.one")
@ModuleEvents(OneModule.Manifest.class)
public class OneModule {
  public static final EventSubscription SUBSCRIPTION =
      new EventSubscription("one.receive", "legacy-two", "fixture.two", 1);

  /** Creates a publisher bound to this factory module. */
  @Bean
  FixturePublisher onePublisher(EventPublisher publisher) {
    return new FixturePublisher(publisher, "legacy-one", "fixture.one");
  }

  /** Creates the local durable effect. */
  @Bean
  static EventHandler oneHandler(JdbcTemplate jdbc) {
    return new FixtureHandler(
        SUBSCRIPTION,
        event ->
            jdbc.update(
                "insert into effects (owner, event_id) values (?, ?)",
                "one",
                event.eventId().toString()));
  }

  /** Pure declaration shared with the runtime handler. */
  public static final class Manifest implements EventDeclarations {
    @Override
    public String identity() {
      return "legacy-one";
    }

    @Override
    public List<EventPublication> publications() {
      return List.of(new EventPublication("fixture.one", 1));
    }

    @Override
    public List<EventSubscription> subscriptions() {
      return List.of(SUBSCRIPTION);
    }
  }
}
