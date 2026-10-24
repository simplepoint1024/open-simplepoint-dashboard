package org.simplepoint.events.composition;

import com.rabbitmq.client.impl.DefaultCredentialsProvider;
import java.util.List;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.CachingConnectionFactoryConfigurer;
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
    if (!(base instanceof CachingConnectionFactory caching)) {
      throw new IllegalStateException(
          "Module events require a configurable Rabbit connection factory");
    }
    var client = caching.getRabbitConnectionFactory().clone();
    client.setCredentialsProvider(new DefaultCredentialsProvider(identity, password));
    client.setCredentialsRefreshService(null);
    if (details.getVirtualHost() != null) {
      client.setVirtualHost(details.getVirtualHost());
    }
    var isolated =
        new RabbitConnectionDetails() {
          @Override
          public String getUsername() {
            return identity;
          }

          @Override
          public String getPassword() {
            return password;
          }

          @Override
          public String getVirtualHost() {
            return details.getVirtualHost();
          }

          @Override
          public List<Address> getAddresses() {
            return details.getAddresses();
          }
        };
    var connection = new CachingConnectionFactory(client);
    new CachingConnectionFactoryConfigurer(properties, isolated).configure(connection);
    connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    connection.setPublisherReturns(true);
    connection.setConnectionNameStrategy(ignored -> "simplepoint-events:" + identity);
    return connection;
  }
}
