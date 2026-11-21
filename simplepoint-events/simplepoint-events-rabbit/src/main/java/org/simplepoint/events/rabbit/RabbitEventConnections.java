package org.simplepoint.events.rabbit;

import com.rabbitmq.client.impl.DefaultCredentialsProvider;
import java.util.List;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.CachingConnectionFactoryConfigurer;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionDetails;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;

/** Builds one broker connection bound to the named logical owner. */
public final class RabbitEventConnections {
  private RabbitEventConnections() {}

  /** Preserves configured TLS and socket behavior while replacing credential sources. */
  public static CachingConnectionFactory isolated(
      ConnectionFactory base, RabbitProperties properties, RabbitConnectionDetails details,
      String identity, String password) {
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
