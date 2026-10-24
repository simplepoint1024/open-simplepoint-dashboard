package org.simplepoint.events.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.events.api.EventDeclarations;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.events.fixture.one.OneModule;
import org.simplepoint.events.fixture.two.TwoModule;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.PlatformModule;

class ModuleEventPlanTest {
  @Test
  void combinedAndSplitPlansPreserveIdentitiesAndAllowExplicitRemoteProducers() {
    var catalog = new ModuleCatalog(List.of(OneModule.class, TwoModule.class));
    var combined = ModuleEventPlan.from(catalog.plan(List.of("one", "two")));
    var split = ModuleEventPlan.from(catalog.plan(List.of("one")));
    assertThat(combined.channels().get("one")).isEqualTo(split.channels().get("one"));
    assertThat(split.channels().get("one").identity()).isEqualTo("legacy-one");
    assertThatThrownBy(() -> split.channels().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void twoModulesCannotBorrowTheSameProducerIdentity() {
    var modules = new ModuleCatalog(List.of(OneModule.class, Duplicate.class));
    assertThatThrownBy(() -> ModuleEventPlan.from(modules.plan(List.of("one", "duplicate"))))
        .hasMessageContaining("DUPLICATE_EVENT_IDENTITY");
  }

  @Test
  void localSubscriptionMustMatchDeclaredPublicationAndManifestDuplicatesAreRejected() {
    var modules = new ModuleCatalog(List.of(OneModule.class, Incompatible.class));
    assertThatThrownBy(() -> ModuleEventPlan.from(modules.plan(List.of("one", "incompatible"))))
        .hasMessageContaining("UNDECLARED_EVENT_PUBLICATION");
    assertThatThrownBy(
            () ->
                new ModuleEventPlan.Channel(
                    "one",
                    "one",
                    List.of(new EventPublication("a", 1), new EventPublication("a", 1)),
                    List.of()))
        .hasMessageContaining("DUPLICATE_EVENT_DECLARATION");
    assertThatThrownBy(
            () ->
                new ModuleEventPlan.Channel(
                    "one",
                    "one",
                    List.of(),
                    List.of(
                        new EventSubscription("effect", "remote", "a", 1),
                        new EventSubscription("effect", "remote", "a", 2))))
        .hasMessageContaining("DUPLICATE_EVENT_DECLARATION");
  }

  @PlatformModule(id = "duplicate", packages = "org.simplepoint.events.fixture.duplicate")
  @ModuleEvents(OneModule.Manifest.class)
  static class Duplicate {}

  @PlatformModule(id = "incompatible", packages = "org.simplepoint.events.fixture.incompatible")
  @ModuleEvents(WrongVersion.class)
  static class Incompatible {}

  /** Deliberately incompatible producer declaration. */
  public static class WrongVersion implements EventDeclarations {
    @Override
    public String identity() {
      return "legacy-two";
    }

    @Override
    public List<EventPublication> publications() {
      return List.of(new EventPublication("fixture.two", 2));
    }

    @Override
    public List<EventSubscription> subscriptions() {
      return List.of();
    }
  }
}
