package com.simplepoint.service.router.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.simplepoint.service.router.annotation.EnableServiceRouter;
import com.simplepoint.service.router.annotation.RoutedMethod;
import com.simplepoint.service.router.annotation.RoutedService;
import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import com.simplepoint.service.router.registry.CapabilityRegistry;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import com.simplepoint.service.router.routing.ServiceRoute;
import com.simplepoint.service.router.web.ServiceRouterController;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.MapPropertySource;

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

  @Test
  void dispatcherConvertsJavaTimeArgumentsWithDefaultMapper() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(JavaTimeProviderConfig.class)) {
      ServiceInvocationDispatcher dispatcher = context.getBean(ServiceInvocationDispatcher.class);
      Instant changedAt = Instant.parse("2026-07-04T10:15:30Z");

      RemoteResponse response = dispatcher.dispatch(new RemoteRequest(
          "sample.AuditService",
          "1.0",
          "record",
          List.of(Map.of("changedAt", changedAt.toString(), "action", "AUTHORIZE")),
          "trace-1"
      ));

      assertThat(response.success()).isTrue();
      assertThat(response.data()).isEqualTo("AUTHORIZE");
      assertThat(context.getBean("capturedAuditInstant", AtomicReference.class).get())
          .isEqualTo(changedAt);
    }
  }

  @Test
  void routesToLocalFactoryMethodProviderThroughTheSameBoundary() {
    try (var context = new AnnotationConfigApplicationContext(LocalProviderConfig.class)) {
      LocalEcho service = context.getBean(LocalEcho.class);

      assertThat(Proxy.isProxyClass(service.getClass())).isTrue();
      assertThat(service.echo("value")).isEqualTo("local:value");
      assertThat(context.getBean(CapabilityRegistry.class).capabilities())
          .extracting(capability -> capability.name()).containsExactly("sample.LocalEcho");
    }
  }

  @Test
  void remoteSelectionIsHonoredEvenWhenLocalProviderExists() {
    try (var context = new AnnotationConfigApplicationContext(LocalProviderConfig.class)) {
      context.getBean(ServiceRouterProperties.class).getConsumer().setLocalFirst(false);

      assertThat(context.getBean(LocalEcho.class).echo("value")).isEqualTo("remote:value");
    }
  }

  @Test
  void disablingRemoteExportsRetainsLocalProvidersAndConsumerCalls() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
          "local-only-host", Map.of("simplepoint.service-router.provider.enabled", "false")));
      context.register(LocalProviderConfig.class);
      context.refresh();

      assertThat(context.getBean(CapabilityRegistry.class).capabilities()).isEmpty();
      assertThat(context.getBeansOfType(ServiceRouterController.class)).isEmpty();
      assertThat(context.getBean(LocalServiceRegistry.class).all()).hasSize(1);
      assertThat(context.getBean(LocalEcho.class).echo("value")).isEqualTo("local:value");
      context.getBean(ServiceRouterProperties.class).getConsumer().setLocalFirst(false);
      assertThat(context.getBean(LocalEcho.class).echo("value")).isEqualTo("remote:value");
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

  @EnableServiceRouter(basePackageClasses = AuditService.class)
  static class JavaTimeProviderConfig {

    @Bean
    AtomicReference<Instant> capturedAuditInstant() {
      return new AtomicReference<>();
    }

    @Bean
    AuditServiceImpl auditService(final AtomicReference<Instant> capturedAuditInstant) {
      return new AuditServiceImpl(capturedAuditInstant);
    }
  }

  @EnableServiceRouter(basePackageClasses = LocalEcho.class)
  static class LocalProviderConfig {

    @Bean
    @Lazy
    LocalEcho localEcho() {
      return new LocalEchoProvider();
    }

    @Bean
    RemoteInvoker remoteInvoker() {
      return (route, request) -> RemoteResponse.success("remote:" + request.args().getFirst());
    }

    @Bean
    ServiceDiscovery serviceDiscovery() {
      return service -> List.of(
          new ServiceRoute("remote", "remote-1", URI.create("http://remote"), Map.of()));
    }
  }

  @RemoteContract(name = "sample.LocalEcho")
  public interface LocalEcho {
    String echo(String value);
  }

  public static class LocalEchoProvider implements LocalEcho {
    @Override
    public String echo(final String value) {
      return "local:" + value;
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

  @RoutedService(name = "sample.AuditService")
  public interface AuditService {

    @RoutedMethod("record")
    String record(AuditCommand command);
  }

  public static class AuditCommand {

    private Instant changedAt;

    private String action;

    public Instant getChangedAt() {
      return changedAt;
    }

    public void setChangedAt(final Instant changedAt) {
      this.changedAt = changedAt;
    }

    public String getAction() {
      return action;
    }

    public void setAction(final String action) {
      this.action = action;
    }
  }

  public static class AuditServiceImpl implements AuditService {

    private final AtomicReference<Instant> capturedAuditInstant;

    AuditServiceImpl(final AtomicReference<Instant> capturedAuditInstant) {
      this.capturedAuditInstant = capturedAuditInstant;
    }

    @Override
    public String record(final AuditCommand command) {
      capturedAuditInstant.set(command.getChangedAt());
      return command.getAction();
    }
  }
}
