package org.simplepoint.data.amqp.rpc.remotecontract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.amqp.rpc.annotation.EnableRemoteClients;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

class EnableRemoteClientsRemoteContractTest {

  @Test
  void enableRemoteClientsShouldRegisterRemoteContractProxy() {
    try (AnnotationConfigApplicationContext context =
             new AnnotationConfigApplicationContext(RemoteContractConfiguration.class)) {
      Object bean = context.getBean(RemoteContractApi.class);

      assertTrue(Proxy.isProxyClass(bean.getClass()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableRemoteClients(basePackageClasses = RemoteContractApi.class)
  static class RemoteContractConfiguration {
  }
}
