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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  @Test
  void dispatchesGenericCollectionArgumentToLocalBean() {
    RoutedServiceMetadata metadata = RoutedServiceMetadataResolver.resolve(MenuLikeService.class).orElseThrow();
    MenuLikeServiceImpl bean = new MenuLikeServiceImpl();
    LocalRoutedService local = new LocalRoutedService(metadata, () -> bean, MenuLikeService.class);
    LocalServiceRegistry registry = new SingleServiceRegistry(local);
    ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(registry, new ObjectMapper());

    RemoteResponse response = dispatcher.dispatch(new RemoteRequest(
        "sample.MenuLikeService",
        "1.0",
        "sync",
        List.of(List.of(Map.of(
            "code",
            "root",
            "children",
            List.of(Map.of("code", "child"))
        ))),
        "trace-1"
    ));

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo(1);
    assertThat(bean.firstChildCode).isEqualTo("child");
  }

  @Test
  void dispatchUnwrapsOptionalReturnValue() {
    RoutedServiceMetadata metadata = RoutedServiceMetadataResolver.resolve(LookupService.class).orElseThrow();
    LocalRoutedService local = new LocalRoutedService(metadata, LookupServiceImpl::new, LookupService.class);
    ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(
        new SingleServiceRegistry(local),
        new ObjectMapper()
    );

    RemoteResponse response = dispatcher.dispatch(new RemoteRequest(
        "sample.LookupService",
        "1.0",
        "find",
        List.of("u1"),
        "trace-1"
    ));

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isEqualTo(new LookupValue("u1", "Ada"));
  }

  @Test
  void dispatchUnwrapsEmptyOptionalReturnValue() {
    RoutedServiceMetadata metadata = RoutedServiceMetadataResolver.resolve(LookupService.class).orElseThrow();
    LocalRoutedService local = new LocalRoutedService(metadata, LookupServiceImpl::new, LookupService.class);
    ServiceInvocationDispatcher dispatcher = new ServiceInvocationDispatcher(
        new SingleServiceRegistry(local),
        new ObjectMapper()
    );

    RemoteResponse response = dispatcher.dispatch(new RemoteRequest(
        "sample.LookupService",
        "1.0",
        "find",
        List.of("missing"),
        "trace-1"
    ));

    assertThat(response.success()).isTrue();
    assertThat(response.data()).isNull();
  }

  @RoutedService(name = "sample.GreetingService")
  interface GreetingService {

    @RoutedMethod("hello")
    String sayHello(String name);
  }

  @RoutedService(name = "sample.MenuLikeService")
  interface MenuLikeService {

    @RoutedMethod("sync")
    int sync(Set<MenuNode> data);
  }

  @RoutedService(name = "sample.LookupService")
  interface LookupService {

    @RoutedMethod("find")
    Optional<LookupValue> find(String id);
  }

  static class GreetingServiceImpl implements GreetingService {

    @Override
    public String sayHello(final String name) {
      return "Hello " + name;
    }
  }

  static class MenuLikeServiceImpl implements MenuLikeService {

    private String firstChildCode;

    @Override
    public int sync(final Set<MenuNode> data) {
      MenuNode root = data.iterator().next();
      firstChildCode = root.children.iterator().next().code;
      return data.size();
    }
  }

  static class LookupServiceImpl implements LookupService {

    @Override
    public Optional<LookupValue> find(final String id) {
      if ("missing".equals(id)) {
        return Optional.empty();
      }
      return Optional.of(new LookupValue(id, "Ada"));
    }
  }

  record LookupValue(String id, String name) {
  }

  static class MenuNode {

    public String code;

    public Set<MenuNode> children;
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
