package com.simplepoint.service.router.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.annotation.RoutedMethod;
import com.simplepoint.service.router.annotation.RoutedService;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.registry.CapabilityRegistry;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;

class EnableServiceRouterIntegrationTest {

  @Test
  void registersProxyForRoutedInterfaceWithoutLocalProvider() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      RemoteEchoService service = context.getBean(RemoteEchoService.class);

      assertThat(service.echo("pong")).isEqualTo("pong");
      assertThat(context.getBean(CapabilityRegistry.class).capabilities()).isEmpty();
    }
  }

  @Test
  void registersProxyForRemoteContractInterfaceWithoutLocalProvider() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
      LegacyEchoService service = context.getBean(LegacyEchoService.class);

      assertThat(service.echo("pong")).isEqualTo("pong");
    }
  }

  @EnableServiceRouter(basePackageClasses = RemoteEchoService.class)
  static class TestConfig {

    @Bean
    RemoteInvoker remoteInvoker() {
      return (route, request) -> RemoteResponse.success(request.args().get(0));
    }

    @Bean
    ServiceDiscovery serviceDiscovery() {
      return service -> List.of(new ServiceRoute("sample", "sample-1", URI.create("http://sample"), Map.of()));
    }
  }

  @RoutedService(name = "sample.RemoteEchoService")
  interface RemoteEchoService {

    @RoutedMethod("echo")
    String echo(String value);
  }

  @RemoteContract(name = "legacy.RemoteEchoService")
  interface LegacyEchoService {

    String echo(String value);
  }
}
