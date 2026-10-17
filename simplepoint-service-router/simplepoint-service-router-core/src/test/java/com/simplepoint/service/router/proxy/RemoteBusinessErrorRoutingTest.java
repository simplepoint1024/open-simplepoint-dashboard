package com.simplepoint.service.router.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.RoutedService;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.exception.ServiceRouterException;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.loadbalance.RoundRobinLoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteBusinessException;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.remoting.RemoteErrorDecoder;
import org.springframework.context.support.StaticApplicationContext;

class RemoteBusinessErrorRoutingTest {

  @Test
  void roundTripsDeclaredErrorsWithoutRetryOrFallback() {
    verifyRejection(false);
  }

  @Test
  void localDeclaredErrorsAlsoBypassFallback() {
    verifyRejection(true);
  }

  private void verifyRejection(boolean local) {
    var calls = new AtomicInteger();
    var fallbackCalls = new AtomicInteger();
    var mapper = new ObjectMapper();
    var metadata = RoutedServiceMetadataResolver.resolve(WriteService.class).orElseThrow();
    WriteService provider = () -> {
      calls.incrementAndGet();
      throw new Conflict("stale version");
    };
    var registry = new Registry(new LocalRoutedService(metadata, () -> provider, WriteService.class));
    var dispatcher = new ServiceInvocationDispatcher(registry, mapper);
    var properties = new ServiceRouterProperties();
    properties.getConsumer().setLocalFirst(local);
    try (var context = new StaticApplicationContext()) {
      context.getBeanFactory().registerSingleton("fallback", new WriteFallback(fallbackCalls));
      var handler = new ServiceRouterInvocationHandler(WriteService.class, metadata, registry,
          ignored -> List.of(new ServiceRoute("write", "write-1", URI.create("http://write"), Map.of())),
          new RoundRobinLoadBalancer(), (route, request) -> {
            // Exercise the actual transport envelope, not an in-memory exception object.
            try {
              return mapper.readValue(mapper.writeValueAsBytes(dispatcher.dispatch(request)), RemoteResponse.class);
            } catch (java.io.IOException ex) {
              throw new AssertionError(ex);
            }
          }, mapper, properties, context);
      var proxy = (WriteService) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {WriteService.class}, handler);
      assertThatThrownBy(proxy::write).isInstanceOf(Conflict.class).hasMessage("stale version");
      assertThat(calls).hasValue(1);
      assertThat(fallbackCalls).hasValue(0);
    }
  }

  @Test
  void unknownWireCodesRemainGenericFailures() {
    var metadata = RoutedServiceMetadataResolver.resolve(UnknownService.class).orElseThrow();
    try (var context = new StaticApplicationContext()) {
      var handler = new ServiceRouterInvocationHandler(UnknownService.class, metadata, new Registry(null),
          ignored -> List.of(new ServiceRoute("unknown", "unknown-1", URI.create("http://unknown"), Map.of())),
          new RoundRobinLoadBalancer(), (route, request) -> RemoteResponse.failure("foreign.Code", "rejected"),
          new ObjectMapper(), new ServiceRouterProperties(), context);
      var proxy = (UnknownService) Proxy.newProxyInstance(getClass().getClassLoader(),
          new Class<?>[] {UnknownService.class}, handler);
      assertThatThrownBy(proxy::write).isInstanceOf(ServiceRouterException.class)
          .hasMessage("foreign.Code: rejected");
    }
  }

  @RemoteContract(name = "write", errorDecoder = Decoder.class)
  @RoutedService(name = "write", retries = 2, fallback = WriteFallback.class)
  public interface WriteService {
    String write();
  }

  @RemoteContract(name = "unknown", errorDecoder = Decoder.class)
  public interface UnknownService {
    String write();
  }

  public static class WriteFallback implements WriteService {
    private final AtomicInteger calls;

    public WriteFallback(AtomicInteger calls) {
      this.calls = calls;
    }

    @Override
    public String write() {
      calls.incrementAndGet();
      return "incorrect success";
    }
  }

  public static class Decoder implements RemoteErrorDecoder {
    @Override
    public RemoteBusinessException decode(String code, String message) {
      return "TEST:CONFLICT".equals(code) ? new Conflict(message) : null;
    }
  }

  private static class Conflict extends RemoteBusinessException {
    Conflict(String message) {
      super(message);
    }

    @Override
    public String remoteErrorCode() {
      return "TEST:CONFLICT";
    }
  }

  private record Registry(LocalRoutedService service) implements LocalServiceRegistry {
    @Override
    public Optional<LocalRoutedService> find(String name, String version) {
      return Optional.ofNullable(service);
    }

    @Override
    public Collection<LocalRoutedService> all() {
      return service == null ? List.of() : List.of(service);
    }
  }
}
