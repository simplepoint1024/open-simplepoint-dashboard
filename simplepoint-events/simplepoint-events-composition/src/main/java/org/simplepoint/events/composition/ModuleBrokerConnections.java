package org.simplepoint.events.composition;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/** Infrastructure extension for independently authenticated module connections. */
@FunctionalInterface
public interface ModuleBrokerConnections {
  /** Creates an owned connection factory; its username must match the stable event identity. */
  CachingConnectionFactory create(String identity, String password);
}
