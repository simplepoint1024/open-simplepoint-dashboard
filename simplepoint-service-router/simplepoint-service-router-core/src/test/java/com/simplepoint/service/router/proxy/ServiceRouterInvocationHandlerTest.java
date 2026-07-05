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

  @Test
  void wrapsOptionalRemoteResponseWithGenericValueType() {
    LookupService proxy = createProxy(
        LookupService.class,
        (route, request) -> RemoteResponse.success(Map.of("id", "u1", "name", "Ada"))
    );

    Optional<LookupValue> result = proxy.find("u1");

    assertThat(result).isPresent();
    assertThat(result.get().id).isEqualTo("u1");
    assertThat(result.get().name).isEqualTo("Ada");
  }

  @Test
  void wrapsNullRemoteResponseAsEmptyOptional() {
    LookupService proxy = createProxy(
        LookupService.class,
        (route, request) -> RemoteResponse.success(null)
    );

    assertThat(proxy.find("missing")).isEmpty();
  }

  @RoutedService(name = "sample.EchoService")
  interface EchoService {

    @RoutedMethod("echo")
    String echo(String value);
  }

  @RoutedService(name = "sample.LookupService")
  interface LookupService {

    @RoutedMethod("find")
    Optional<LookupValue> find(String id);
  }

  static class LookupValue {

    public String id;

    public String name;
  }

  @SuppressWarnings("unchecked")
  private static <T> T createProxy(
      final Class<T> serviceType,
      final com.simplepoint.service.router.invocation.RemoteInvoker remoteInvoker
  ) {
    RoutedServiceMetadata metadata = RoutedServiceMetadataResolver.resolve(serviceType).orElseThrow();
    ServiceRouterInvocationHandler handler = new ServiceRouterInvocationHandler(
        serviceType,
        metadata,
        new EmptyLocalServiceRegistry(),
        service -> List.of(new ServiceRoute("sample", "sample-1", URI.create("http://sample"), Map.of())),
        new RoundRobinLoadBalancer(),
        remoteInvoker,
        new ObjectMapper(),
        new ServiceRouterProperties(),
        new StaticApplicationContext()
    );
    return (T) Proxy.newProxyInstance(
        serviceType.getClassLoader(),
        new Class<?>[] {serviceType},
        handler
    );
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
