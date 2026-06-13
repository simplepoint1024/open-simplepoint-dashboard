package com.simplepoint.service.router.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.RoutedMethod;
import com.simplepoint.service.router.annotation.RoutedService;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.loadbalance.RoundRobinLoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

class ServiceRouterInvocationHandlerTest {

  @Test
  void invokesRemoteProviderWhenNoLocalBeanExists() {
    RoutedServiceMetadata metadata = RoutedServiceMetadataResolver.resolve(EchoService.class).orElseThrow();
    ServiceRouterInvocationHandler handler = new ServiceRouterInvocationHandler(
        EchoService.class,
        metadata,
        new EmptyLocalServiceRegistry(),
        service -> List.of(new ServiceRoute("echo", "echo-1", URI.create("http://echo"), Map.of())),
        new RoundRobinLoadBalancer(),
        (route, request) -> RemoteResponse.success(request.args().get(0)),
        new ObjectMapper(),
        new ServiceRouterProperties(),
        new StaticApplicationContext()
    );
    EchoService proxy = (EchoService) Proxy.newProxyInstance(
        EchoService.class.getClassLoader(),
        new Class<?>[] {EchoService.class},
        handler
    );

    assertThat(proxy.echo("pong")).isEqualTo("pong");
  }

  @RoutedService(name = "sample.EchoService")
  interface EchoService {

    @RoutedMethod("echo")
    String echo(String value);
  }

  private static class EmptyLocalServiceRegistry implements LocalServiceRegistry {

    @Override
    public Optional<com.simplepoint.service.router.registry.LocalRoutedService> find(
        final String service,
        final String version
    ) {
      return Optional.empty();
    }

    @Override
    public java.util.Collection<com.simplepoint.service.router.registry.LocalRoutedService> all() {
      return List.of();
    }
  }
}
