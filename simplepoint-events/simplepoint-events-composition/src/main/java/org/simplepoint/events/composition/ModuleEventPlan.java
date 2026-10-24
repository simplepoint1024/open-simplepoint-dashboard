package org.simplepoint.events.composition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublication;
import org.simplepoint.events.api.EventSubscription;
import org.simplepoint.events.api.ModuleEvents;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;

/** Immutable event assembly plan, independent of connections, secrets and physical host names. */
public record ModuleEventPlan(Map<String, Channel> channels) {
  /** Freezes the deterministic module-order plan. */
  public ModuleEventPlan {
    channels = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(channels));
  }

  /** Reads selected module manifests once and rejects ambiguous or incompatible local ownership. */
  public static ModuleEventPlan from(ModulePlan modules) {
    var channels = new LinkedHashMap<String, Channel>();
    var identities = new LinkedHashMap<String, Channel>();
    for (var module : modules.modules()) {
      var declaration = module.configuration().getDeclaredAnnotation(ModuleEvents.class);
      if (declaration == null) {
        continue;
      }
      try {
        var manifest = declaration.value().getConstructor().newInstance();
        var channel =
            new Channel(
                module.id(),
                manifest.identity(),
                manifest.publications(),
                manifest.subscriptions());
        if (identities.putIfAbsent(channel.identity(), channel) != null) {
          throw failure("DUPLICATE_EVENT_IDENTITY", channel.identity());
        }
        channels.put(module.id(), channel);
      } catch (ReflectiveOperationException ex) {
        throw failure("INVALID_EVENT_MANIFEST", declaration.value().getName());
      }
    }
    for (var channel : channels.values()) {
      for (var subscription : channel.subscriptions()) {
        var local = identities.get(subscription.producer());
        if (local != null
            && !local
                .publications()
                .contains(
                    new EventPublication(subscription.eventType(), subscription.schemaVersion()))) {
          throw failure("UNDECLARED_EVENT_PUBLICATION", local.module() + ":" + subscription.id());
        }
      }
    }
    return new ModuleEventPlan(channels);
  }

  /** One stable event identity bound to exactly one logical module. */
  public record Channel(
      String module,
      String identity,
      List<EventPublication> publications,
      List<EventSubscription> subscriptions) {
    /** Copies contracts and rejects duplicate declarations before runtime effects. */
    public Channel {
      EventMessage.token(identity, "event identity");
      publications = List.copyOf(publications);
      subscriptions = List.copyOf(subscriptions);
      if (publications.isEmpty() && subscriptions.isEmpty()) {
        throw failure("EMPTY_EVENT_MANIFEST", module);
      }
      if (Set.copyOf(publications).size() != publications.size()
          || subscriptions.stream().map(EventSubscription::id).distinct().count()
              != subscriptions.size()) {
        throw failure("DUPLICATE_EVENT_DECLARATION", module);
      }
    }
  }

  private static ModuleCompositionException failure(String code, String detail) {
    return new ModuleCompositionException(code, detail);
  }
}
