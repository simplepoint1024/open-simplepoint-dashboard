package org.simplepoint.events.api;

import java.util.List;

/** Authoritative event contracts shared by runtime assembly and deployment provisioning. */
public interface EventDeclarations {
  /** Returns the stable event identity, which need not equal the module or deployment host name. */
  String identity();

  /** Returns the versions this owner may publish. */
  List<EventPublication> publications();

  /** Returns the durable local effects, including their explicitly trusted producers. */
  List<EventSubscription> subscriptions();
}
