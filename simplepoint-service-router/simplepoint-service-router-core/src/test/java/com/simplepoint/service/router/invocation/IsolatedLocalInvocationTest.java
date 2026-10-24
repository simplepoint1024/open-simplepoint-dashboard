package com.simplepoint.service.router.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplepoint.service.router.config.ServiceRouterProperties;
import com.simplepoint.service.router.exception.RemoteServiceFailureException;
import com.simplepoint.service.router.exception.ServiceRouterException;
import com.simplepoint.service.router.loadbalance.RoundRobinLoadBalancer;
import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.proxy.ServiceRouterInvocationHandler;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteBusinessException;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.remoting.RemoteErrorDecoder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class IsolatedLocalInvocationTest {
  private static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();

  @Test
  void wireCopiesArgumentsAndResultsWithoutInheritingRequestState() {
    var provider = new Provider(null);
    try (var fixture = new Fixture(provider, Duration.ofSeconds(2), List.of())) {
      var request = new ServletRequestAttributes(new MockHttpServletRequest());
      RequestContextHolder.setRequestAttributes(request, true);
      INHERITED.set("browser-user");
      try {
        var input = new LinkedHashMap<String, Object>();
        input.put("amount", new BigDecimal("1234567890.1234567890123456789"));
        var result = fixture.proxy.inspect(input);
        assertThat(input).doesNotContainKey("provider");
        assertThat(result)
            .containsEntry("provider", true)
            .containsEntry("amount", input.get("amount"))
            .containsEntry("requestAbsent", true)
            .containsEntry("inheritedAbsent", true);
        result.put("client", true);
        assertThat(provider.retained).doesNotContainKey("client");
        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(request);
        assertThat(INHERITED.get()).isEqualTo("browser-user");
      } finally {
        RequestContextHolder.resetRequestAttributes();
        INHERITED.remove();
      }
    }
  }

  @Test
  void providerTransactionCommitsIndependentlyAndFailureDoesNotPoisonCaller() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:boundary-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table effects (name varchar(100) primary key)");
    var transactions = new DataSourceTransactionManager(dataSource);
    var outer = new TransactionTemplate(transactions);
    var provider = new Provider(jdbc);
    var proxyFactory = new ProxyFactory(provider);
    proxyFactory.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    try (var fixture =
        new Fixture((Contract) proxyFactory.getProxy(), Duration.ofSeconds(2), List.of())) {
      outer.executeWithoutResult(
          status -> {
            jdbc.update("insert into effects values ('caller-rollback')");
            fixture.proxy.write("provider-commit", false);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            status.setRollbackOnly();
          });
      assertThat(jdbc.queryForList("select name from effects", String.class))
          .containsExactly("provider-commit");
      outer.executeWithoutResult(
          status -> {
            jdbc.update("insert into effects values ('caller-commit')");
            assertThatThrownBy(() -> fixture.proxy.write("provider-rollback", true))
                .isInstanceOf(Conflict.class)
                .hasMessage("Conflict");
            assertThat(status.isRollbackOnly()).isFalse();
          });
      assertThat(jdbc.queryForList("select name from effects", String.class))
          .containsExactlyInAnyOrder("provider-commit", "caller-commit");
    } finally {
      jdbc.execute("shutdown");
    }
  }

  @Test
  void nestedCallsShareCapacityButHaveBoundedDepthAndCloseContextInOrder() {
    var opened = new AtomicInteger();
    var closed = new AtomicInteger();
    LocalInvocationContext contribution =
        () -> {
          opened.incrementAndGet();
          return closed::incrementAndGet;
        };
    var provider = new Provider(null);
    try (var fixture = new Fixture(provider, Duration.ofSeconds(2), List.of(contribution))) {
      provider.recursive = fixture.proxy;
      assertThat(fixture.proxy.recurse(2)).isEqualTo(3);
      assertThat(opened.get()).isEqualTo(3);
      assertThat(closed.get()).isEqualTo(3);
      assertThatThrownBy(() -> fixture.proxy.recurse(20))
          .isInstanceOf(RemoteServiceFailureException.class);
      assertThat(opened.get()).isEqualTo(7);
      assertThat(closed.get()).isEqualTo(7);
      assertThat(fixture.proxy.recurse(0)).isEqualTo(1);
    }
  }

  @Test
  void timedOutWriteIsNotRetriedAndKeepsCapacityUntilWorkerActuallyExits() throws Exception {
    var provider = new Provider(null);
    try (var fixture = new Fixture(provider, Duration.ofSeconds(1), List.of())) {
      try {
        assertThatThrownBy(fixture.proxy::hold)
            .isInstanceOf(ServiceRouterException.class)
            .hasMessageContaining("completion is uncertain");
        assertThat(provider.entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> fixture.proxy.recurse(0))
            .isInstanceOf(ServiceRouterException.class)
            .hasMessageContaining("capacity exhausted");
        assertThat(provider.writes.get()).isEqualTo(1);
      } finally {
        provider.release.release();
      }
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(fixture.proxy.recurse(0)).isEqualTo(1));
      assertThat(provider.writes.get()).isEqualTo(1);
    }
  }

  @Test
  void contextOpenFailurePreventsProviderAndClosesAlreadyOpenedScopes() {
    var closed = new AtomicInteger();
    LocalInvocationContext first = () -> closed::incrementAndGet;
    LocalInvocationContext failure =
        () -> {
          throw new IllegalStateException("rejected");
        };
    var provider = new Provider(null);
    try (var fixture = new Fixture(provider, Duration.ofSeconds(2), List.of(first, failure))) {
      assertThatThrownBy(() -> fixture.proxy.write("never", false))
          .isInstanceOf(ServiceRouterException.class);
      assertThat(provider.writes.get()).isZero();
      assertThat(closed.get()).isEqualTo(1);
    }
  }

  @Test
  void slowAuthenticationCannotStartBusinessWorkAfterTheCallerDeadline() {
    var release = new Semaphore(0);
    var closed = new AtomicInteger();
    LocalInvocationContext slow =
        () -> {
          release.acquireUninterruptibly();
          return closed::incrementAndGet;
        };
    var provider = new Provider(null);
    try (var fixture = new Fixture(provider, Duration.ofSeconds(1), List.of(slow))) {
      try {
        assertThatThrownBy(() -> fixture.proxy.write("never", false))
            .isInstanceOf(ServiceRouterException.class)
            .hasMessageContaining("completion is uncertain");
      } finally {
        release.release();
      }
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(closed.get()).isEqualTo(1));
      assertThat(provider.writes.get()).isZero();
    }
  }

  @Test
  void timedOutNestedWorkerRetainsTheRootCapacity() {
    var provider = new Provider(null);
    try (var fixture = new Fixture(provider, Duration.ofSeconds(1), List.of())) {
      provider.recursive = fixture.proxy;
      try {
        assertThatThrownBy(fixture.proxy::holdNested).isInstanceOf(ServiceRouterException.class);
        assertThat(provider.writes.get()).isEqualTo(1);
        assertThatThrownBy(() -> fixture.proxy.recurse(0))
            .isInstanceOf(ServiceRouterException.class)
            .hasMessageContaining("capacity exhausted");
      } finally {
        provider.release.release();
      }
      await()
          .atMost(Duration.ofSeconds(2))
          .untilAsserted(() -> assertThat(fixture.proxy.recurse(0)).isEqualTo(1));
    }
  }

  @Test
  void shutdownStopsWaitingCallersAndRejectsFurtherWork() throws Exception {
    var provider = new Provider(null);
    try (var fixture = new Fixture(provider, Duration.ofSeconds(5), List.of())) {
      var waiting = CompletableFuture.runAsync(fixture.proxy::hold);
      try {
        assertThat(provider.entered.await(1, TimeUnit.SECONDS)).isTrue();
        fixture.invoker.close();
        assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS))
            .hasCauseInstanceOf(ServiceRouterException.class);
        assertThatThrownBy(() -> fixture.proxy.recurse(0))
            .isInstanceOf(ServiceRouterException.class);
        assertThat(provider.writes.get()).isEqualTo(1);
      } finally {
        provider.release.release();
      }
    }
  }

  @RemoteContract(name = "isolated.test", errorDecoder = Decoder.class)
  public interface Contract {
    Map<String, Object> inspect(Map<String, Object> input);

    void write(String name, boolean fail);

    int recurse(int remaining);

    void hold();

    void holdNested();
  }

  public static class Decoder implements RemoteErrorDecoder {
    @Override
    public RemoteBusinessException decode(String code, String message) {
      return "TEST:CONFLICT".equals(code) ? new Conflict() : null;
    }
  }

  static class Conflict extends RemoteBusinessException {
    Conflict() {
      super("Conflict");
    }

    @Override
    public String remoteErrorCode() {
      return "TEST:CONFLICT";
    }
  }

  public static class Provider implements Contract {
    final JdbcTemplate jdbc;
    final AtomicInteger writes = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    final Semaphore release = new Semaphore(0);
    Map<String, Object> retained;
    Contract recursive;

    Provider(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Override
    public Map<String, Object> inspect(Map<String, Object> input) {
      input.put("provider", true);
      input.put("requestAbsent", RequestContextHolder.getRequestAttributes() == null);
      input.put("inheritedAbsent", INHERITED.get() == null);
      retained = input;
      return input;
    }

    @Override
    @Transactional
    public void write(String name, boolean fail) {
      writes.incrementAndGet();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      jdbc.update("insert into effects values (?)", name);
      if (fail) {
        throw new Conflict();
      }
    }

    @Override
    public int recurse(int remaining) {
      return remaining == 0 ? 1 : 1 + recursive.recurse(remaining - 1);
    }

    @Override
    public void hold() {
      writes.incrementAndGet();
      entered.countDown();
      release.acquireUninterruptibly();
    }

    @Override
    public void holdNested() {
      recursive.hold();
    }
  }

  private static class Fixture implements AutoCloseable {
    final StaticApplicationContext context = new StaticApplicationContext();
    final Contract proxy;
    final IsolatedLocalInvoker invoker;

    Fixture(Contract provider, Duration timeout, List<LocalInvocationContext> contexts) {
      var mapper = new ObjectMapper();
      var metadata = RoutedServiceMetadataResolver.resolve(Contract.class).orElseThrow();
      var local = new LocalRoutedService(metadata, () -> provider, Contract.class);
      var registry =
          new LocalServiceRegistry() {
            @Override
            public Optional<LocalRoutedService> find(String name, String version) {
              return Optional.of(local);
            }

            @Override
            public Collection<LocalRoutedService> all() {
              return List.of(local);
            }
          };
      invoker =
          new IsolatedLocalInvoker(
              new ServiceInvocationDispatcher(registry, mapper), mapper, contexts, 1, 4, timeout);
      context.getBeanFactory().registerSingleton("isolatedLocalInvoker", invoker);
      var properties = new ServiceRouterProperties();
      properties.getConsumer().setLocalIsolation(true);
      var handler =
          new ServiceRouterInvocationHandler(
              Contract.class,
              metadata,
              registry,
              ignored -> List.of(),
              new RoundRobinLoadBalancer(),
              (route, request) -> {
                throw new AssertionError("Local invocation must not use a remote route");
              },
              mapper,
              properties,
              context);
      proxy =
          (Contract)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(), new Class<?>[] {Contract.class}, handler);
    }

    @Override
    public void close() {
      invoker.close();
      context.close();
    }
  }
}
