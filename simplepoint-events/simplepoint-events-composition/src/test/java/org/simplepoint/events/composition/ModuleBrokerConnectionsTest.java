package org.simplepoint.events.composition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.RabbitConnectionDetails;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;

class ModuleBrokerConnectionsTest {
  @Test
  void connectionsKeepTlsAndEndpointSettingsButNeverShareHostCredentials() throws Exception {
    var client = new com.rabbitmq.client.ConnectionFactory();
    client.useSslProtocol();
    client.setUsername("host");
    client.setPassword("test-host-secret");
    var base = new CachingConnectionFactory(client);
    var details =
        new RabbitConnectionDetails() {
          public List<Address> getAddresses() {
            return List.of(new Address("broker.example.test", 5671));
          }

          public String getVirtualHost() {
            return "/isolated";
          }
        };
    var factory = new DefaultModuleBrokerConnections(base, new RabbitProperties(), details);
    var one = factory.create("legacy-one", "test-one-secret");
    var two = factory.create("legacy-two", "test-two-secret");
    try {
      assertThat(one.getRabbitConnectionFactory())
          .isNotSameAs(two.getRabbitConnectionFactory())
          .isNotSameAs(client);
      assertThat(one.getRabbitConnectionFactory().isSSL()).isTrue();
      assertThat(one.getUsername()).isEqualTo("legacy-one");
      assertThat(two.getUsername()).isEqualTo("legacy-two");
      assertThat(one.getVirtualHost()).isEqualTo("/isolated");
      assertThat(base.getUsername()).isEqualTo("host");
      assertThat(one.isPublisherConfirms()).isTrue();
      assertThat(one.isPublisherReturns()).isTrue();
    } finally {
      one.destroy();
      two.destroy();
      base.destroy();
    }
  }
}
