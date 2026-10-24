package com.simplepoint.service.router.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.invocation.IsolatedLocalInvoker;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.loadbalance.RoundRobinLoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.context.support.StaticApplicationContext;

class RequiredLocalIsolationTest {
  @Test
  void providerRequirementAlsoAppliesToAnOlderConsumerContract() {
    final Thread caller = Thread.currentThread();
    var inherited = new InheritableThreadLocal<String>();
    inherited.set("caller-state");
    ProviderContract provider =
        value -> {
          assertThat(Thread.currentThread()).isNotSameAs(caller);
          assertThat(inherited.get()).isNull();
          return value + "-processed";
        };
    var metadata = RoutedServiceMetadataResolver.resolve(ProviderContract.class).orElseThrow();
    var local = new LocalRoutedService(metadata, () -> provider, ProviderContract.class);
    var registry =
        new LocalServiceRegistry() {
          @Override
          public Optional<LocalRoutedService> find(String service, String version) {
            return Optional.of(local);
          }

          @Override
          public Collection<LocalRoutedService> all() {
            return List.of(local);
          }
        };
    var mapper = new ObjectMapper();
    var properties = new ServiceRouterProperties();
    properties.getConsumer().setLocalIsolation(false);
    try (var context = new StaticApplicationContext();
        var isolated =
            new IsolatedLocalInvoker(
                new ServiceInvocationDispatcher(registry, mapper),
                mapper,
                List.of(),
                4,
                4,
                Duration.ofSeconds(5))) {
      context.getBeanFactory().registerSingleton("isolatedLocalInvoker", isolated);
      var handler =
          new ServiceRouterInvocationHandler(
              LegacyConsumer.class,
              metadata,
              registry,
              ignored -> {
                throw new AssertionError("Local provider must be selected");
              },
              new RoundRobinLoadBalancer(),
              (route, request) -> {
                throw new AssertionError("Unexpected network call");
              },
              mapper,
              properties,
              context);
      var client =
          (LegacyConsumer)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(), new Class<?>[] {LegacyConsumer.class}, handler);
      assertThat(client.write("payload")).isEqualTo("payload-processed");
      assertThat(inherited.get()).isEqualTo("caller-state");
    } finally {
      inherited.remove();
    }
  }

  @RemoteContract(name = "required-isolation", isolateLocalCalls = true)
  public interface ProviderContract {
    String write(String value);
  }

  @RemoteContract(name = "required-isolation")
  public interface LegacyConsumer {
    String write(String value);
  }
}
