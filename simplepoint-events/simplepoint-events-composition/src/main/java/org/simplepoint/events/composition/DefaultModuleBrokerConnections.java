package org.simplepoint.events.composition;

import org.simplepoint.events.rabbit.RabbitEventConnections;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionDetails;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;

/** Preserves configured TLS/socket behavior while replacing every shared credential source. */
final class DefaultModuleBrokerConnections implements ModuleBrokerConnections {
  private final ConnectionFactory base;
  private final RabbitProperties properties;
  private final RabbitConnectionDetails details;

  DefaultModuleBrokerConnections(
      ConnectionFactory base, RabbitProperties properties, RabbitConnectionDetails details) {
    this.base = base;
    this.properties = properties;
    this.details = details;
  }

  @Override
  public CachingConnectionFactory create(String identity, String password) {
    return RabbitEventConnections.isolated(base, properties, details, identity, password);
  }
}
