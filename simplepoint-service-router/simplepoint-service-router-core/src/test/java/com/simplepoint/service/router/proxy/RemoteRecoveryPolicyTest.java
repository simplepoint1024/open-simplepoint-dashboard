package com.simplepoint.service.router.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.annotation.RoutedService;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.exception.RemoteServiceFailureException;
import com.simplepoint.service.router.exception.ServiceRouterException;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.loadbalance.RoundRobinLoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteOperation;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class RemoteRecoveryPolicyTest {

  @Test
  void committedWriteWithLostResponseIsNeverRepeatedOrReplacedByFallback() {
    var commits = new AtomicInteger();
    try (var fixture = new Fixture((route, request) -> {
      commits.incrementAndGet();
      throw new ResourceAccessException("Response lost after commit");
    }, null)) {
      assertThatThrownBy(fixture.proxy::write).isInstanceOf(ResourceAccessException.class);
      assertThat(commits).hasValue(1);
      assertThat(fixture.fallback.calls).hasValue(0);
    }
  }

  @Test
  void serverFailureAfterWriteDoesNotReplay() {
    var calls = new AtomicInteger();
    try (var fixture = new Fixture((route, request) -> {
      calls.incrementAndGet();
      throw new HttpServerErrorException(HttpStatus.BAD_GATEWAY);
    }, null)) {
      assertThatThrownBy(fixture.proxy::write).isInstanceOf(HttpServerErrorException.class);
      assertThat(calls).hasValue(1);
      assertThat(fixture.fallback.calls).hasValue(0);
    }
  }

  @Test
  void explicitlyIdempotentOperationRetriesWithTheSameTrace() {
    var traces = new ArrayList<String>();
    try (var fixture = new Fixture((route, request) -> {
      traces.add(request.traceId());
      if (traces.size() < 3) {
        throw new ResourceAccessException("Transient transport failure");
      }
      return RemoteResponse.success("result");
    }, null)) {
      assertThat(fixture.proxy.read()).isEqualTo("result");
      assertThat(traces).hasSize(3).doesNotContainNull();
      assertThat(traces.stream().distinct()).hasSize(1);
      assertThat(fixture.fallback.calls).hasValue(0);
    }
  }

  @Test
  void idempotentOperationMayUseFallbackOnlyAfterTransportRetriesAreExhausted() {
    var calls = new AtomicInteger();
    try (var fixture = new Fixture((route, request) -> {
      calls.incrementAndGet();
      throw new ResourceAccessException("Transport unavailable");
    }, null)) {
      assertThat(fixture.proxy.read()).isEqualTo("fallback");
      assertThat(calls).hasValue(3);
      assertThat(fixture.fallback.calls).hasValue(1);
    }
  }

  @Test
  void unknownProviderErrorIsNotRetriedEvenForIdempotentOperations() {
    var calls = new AtomicInteger();
    try (var fixture = new Fixture((route, request) -> {
      calls.incrementAndGet();
      return RemoteResponse.failure("UNKNOWN_DOMAIN_ERROR", "Rejected");
    }, null)) {
      assertThatThrownBy(fixture.proxy::read).isInstanceOf(RemoteServiceFailureException.class);
      assertThat(calls).hasValue(1);
      assertThat(fixture.fallback.calls).hasValue(0);
    }
  }

  @Test
  void authenticationAndProtocolFailuresDoNotRetryOrUseFallback() {
    for (var failure : List.of(
        new HttpClientErrorException(HttpStatus.UNAUTHORIZED),
        new HttpClientErrorException(HttpStatus.FORBIDDEN),
        new ServiceRouterException("Invalid response envelope"))) {
      var calls = new AtomicInteger();
      try (var fixture = new Fixture((route, request) -> {
        calls.incrementAndGet();
        throw failure;
      }, null)) {
        assertThatThrownBy(fixture.proxy::read).isSameAs(failure);
        assertThat(calls).hasValue(1);
        assertThat(fixture.fallback.calls).hasValue(0);
      }
    }
  }

  @Test
  void emptyOrIncompatibleResultDoesNotReplayCompletedInvocation() {
    for (boolean empty : List.of(true, false)) {
      var calls = new AtomicInteger();
      try (var fixture = new Fixture((route, request) -> {
        calls.incrementAndGet();
        return empty ? null : RemoteResponse.success(Map.of("unexpected", "structure"));
      }, null)) {
        assertThatThrownBy(fixture.proxy::read).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(1);
        assertThat(fixture.fallback.calls).hasValue(0);
      }
    }
  }

  @Test
  void localFailureNeverTriggersRemoteRecoveryOrFallback() {
    for (var failure : List.of(
        new SecurityException("Caller rejected"),
        new ResourceAccessException("Local provider dependency failed"))) {
      Operations local = new Operations() {
        @Override
        public String write() {
          throw failure;
        }

        @Override
        public String read() {
          throw failure;
        }
      };
      try (var fixture = new Fixture((route, request) -> {
        throw new AssertionError("Local failures must not invoke remote providers");
      }, local)) {
        assertThatThrownBy(fixture.proxy::read).isSameAs(failure);
        assertThat(fixture.fallback.calls).hasValue(0);
      }
    }
  }

  @RoutedService(name = "recovery", retries = 2, fallback = Fallback.class)
  public interface Operations {
    String write();

    @RemoteOperation(idempotent = true)
    String read();
  }

  public static class Fallback implements Operations {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String write() {
      calls.incrementAndGet();
      return "fallback";
    }

    @Override
    public String read() {
      calls.incrementAndGet();
      return "fallback";
    }
  }

  private static class Fixture implements AutoCloseable {
    final StaticApplicationContext context = new StaticApplicationContext();
    final Fallback fallback = new Fallback();
    final Operations proxy;

    Fixture(RemoteInvoker invoker, Operations local) {
      context.getBeanFactory().registerSingleton("fallback", fallback);
      var metadata = RoutedServiceMetadataResolver.resolve(Operations.class).orElseThrow();
      var provider = local == null ? null : new LocalRoutedService(metadata, () -> local, Operations.class);
      var handler = new ServiceRouterInvocationHandler(Operations.class, metadata, new Registry(provider),
          ignored -> List.of(new ServiceRoute("recovery", "one", URI.create("http://recovery"), Map.of())),
          new RoundRobinLoadBalancer(), invoker, new ObjectMapper(), new ServiceRouterProperties(), context);
      proxy = (Operations) Proxy.newProxyInstance(Operations.class.getClassLoader(),
          new Class<?>[] {Operations.class}, handler);
    }

    @Override
    public void close() {
      context.close();
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
