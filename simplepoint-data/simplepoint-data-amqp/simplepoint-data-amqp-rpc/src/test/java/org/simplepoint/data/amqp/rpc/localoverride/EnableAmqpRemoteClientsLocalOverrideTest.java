package org.simplepoint.data.amqp.rpc.localoverride;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.data.amqp.rpc.annotation.EnableAmqpRemoteClients;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class EnableAmqpRemoteClientsLocalOverrideTest {

  @Test
  void enableAmqpRemoteClientsShouldPreferLocalImplementation() {
    try (AnnotationConfigApplicationContext context =
             new AnnotationConfigApplicationContext(LocalOverrideConfiguration.class)) {
      assertFalse(context.containsBeanDefinition(LocalOverrideApi.class.getName()));
      assertArrayEquals(new String[] {"localOverrideService"},
          context.getBeanNamesForType(LocalOverrideApi.class, false, false));
      assertEquals("pong", context.getBean(LocalOverrideApi.class).ping());
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAmqpRemoteClients(basePackageClasses = LocalOverrideApi.class)
  static class LocalOverrideConfiguration {

    @Bean
    LocalOverrideService localOverrideService() {
      return new LocalOverrideService();
    }
  }

  @AmqpRemoteService
  static final class LocalOverrideService implements LocalOverrideApi {

    @Override
    public String ping() {
      return "pong";
    }
  }
}
