package com.simplepoint.service.router.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.RoutedMethod;
import com.simplepoint.service.router.annotation.RoutedService;
import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceInvocationDispatcherTest {

  @Test
  void dispatchesRemoteRequestToLocalBean() {
    RoutedServiceMetadata metadata = RoutedServiceMetadataResolver.resolve(GreetingService.class).orElseThrow();
    GreetingServiceImpl bean = new GreetingServiceImpl();
    LocalRoutedService local = new LocalRoutedService(metadata, () -> bean, GreetingService.class);
    LocalServiceRegistry registry = new SingleServiceRegistry(local);
    ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(registry, new ObjectMapper());

    RemoteResponse response = dispatcher.dispatch(
        new RemoteRequest("sample.GreetingService", "1.0", "hello", List.of("Ada"), "trace-1")
    );

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo("Hello Ada");
  }

  @RoutedService(name = "sample.GreetingService")
  interface GreetingService {

    @RoutedMethod("hello")
    String sayHello(String name);
  }

  static class GreetingServiceImpl implements GreetingService {

    @Override
    public String sayHello(final String name) {
      return "Hello " + name;
    }
  }

  private record SingleServiceRegistry(LocalRoutedService local) implements LocalServiceRegistry {

    @Override
    public Optional<LocalRoutedService> find(final String service, final String version) {
      if (local.metadata().name().equals(service) && local.metadata().version().equals(version)) {
        return Optional.of(local);
      }
      return Optional.empty();
    }

    @Override
    public Collection<LocalRoutedService> all() {
      return List.of(local);
    }
  }
}
