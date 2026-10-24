package org.simplepoint.platform.composition.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.composition.fixture.api.BootstrapEffects;
import org.simplepoint.platform.bootstrap.BootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapAutoConfiguration;
import org.simplepoint.platform.bootstrap.PlatformBootstrapContribution;
import org.simplepoint.platform.bootstrap.PlatformBootstrapExecutor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ModuleBootstrapBootTest {
  private final BootstrapEffects effects = new BootstrapEffects();
  private final List<String> calls = new ArrayList<>();
  private final Set<String> applied = new HashSet<>();

  @Test
  void splitAndMergedHostsKeepTheSameRegistryKeysAndOwningCredentials() {
    try (var context = start("bootstrap-one,bootstrap-two", "aggregate", Map.of())) {
      assertThat(context.getBeansOfType(PlatformBootstrapExecutor.class)).isEmpty();
      assertThat(effects.entries()).containsExactly("two", "one");
      assertThat(applied)
          .containsExactlyInAnyOrder(
              "module/bootstrap-one:data:same-key", "module/bootstrap-two:data:same-key");
      assertThat(calls)
          .allMatch(
              call ->
                  call.startsWith("bootstrap-one:module/bootstrap-one:")
                      || call.startsWith("bootstrap-two:module/bootstrap-two:"));
    }
    calls.clear();
    try (var ignored = start("bootstrap-one", "new-independent-host", Map.of())) {
      assertThat(effects.entries()).containsExactly("two", "one");
      assertThat(calls).singleElement().asString().contains("shouldApply");
    }
  }

  @Test
  void baseDataPreservesCrossModulePrioritiesAndRunsBeforeRegistration() {
    try (var ignored =
        start("bootstrap-one,bootstrap-two", "host", Map.of("fixture.ordered", "true"))) {
      assertThat(effects.entries()).containsExactly("one-early", "two", "one", "one-registration");
    }
  }

  @Test
  void modulePolicyIsIndependentOfLegacyServiceAllowlist() {
    try (var ignored =
        start(
            "bootstrap-one,bootstrap-two",
            "not-common",
            Map.of("simplepoint.platform.bootstrap.modules", "bootstrap-two"))) {
      assertThat(effects.entries()).containsExactly("two");
      assertThat(calls).allMatch(call -> call.startsWith("bootstrap-two:"));
    }
  }

  @Test
  void duplicateIdentityFailsBeforeRegistryOrBusinessEffects() {
    assertThatThrownBy(() -> start("bootstrap-one", "host", Map.of("fixture.duplicate", "true")))
        .hasStackTraceContaining("DUPLICATE_BOOTSTRAP_CONTRIBUTION");
    assertThat(calls).isEmpty();
    assertThat(effects.entries()).isEmpty();
  }

  @Test
  void missingModuleDeclarationCannotSilentlySkipDiscoveredBootstrapProviders() {
    assertThatThrownBy(() -> start("bootstrap-undeclared", "host", Map.of()))
        .hasStackTraceContaining("UNDECLARED_MODULE_BOOTSTRAP");
    assertThat(calls).isEmpty();
  }

  @Test
  void unownedProviderCannotBorrowModuleCredentials() {
    assertThatThrownBy(() -> start("bootstrap-one", "host", Map.of(), Unowned.class))
        .hasStackTraceContaining("UNOWNED_BOOTSTRAP_CONTRIBUTION");
    assertThat(calls).isEmpty();
  }

  @Test
  void modularBootstrapCannotClaimReadinessAfterIgnoringRequiredFailures() {
    assertThatThrownBy(
            () ->
                start(
                    "bootstrap-one",
                    "host",
                    Map.of("simplepoint.platform.bootstrap.fail-fast", "false")))
        .hasStackTraceContaining("MODULE_BOOTSTRAP_FAIL_FAST_REQUIRED");
    assertThat(calls).isEmpty();
  }

  private ConfigurableApplicationContext start(
      String roots, String host, Map<String, Object> properties, Class<?>... extra) {
    var sources = new ArrayList<Class<?>>();
    sources.add(Host.class);
    sources.addAll(List.of(extra));
    var application = new SpringApplication(sources.toArray(Class<?>[]::new));
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    application.setLogStartupInfo(false);
    var settings = new java.util.HashMap<String, Object>(properties);
    settings.put("spring.main.banner-mode", "off");
    settings.put("spring.application.name", host);
    settings.put("simplepoint.modules.active", roots);
    settings.put("simplepoint.service-router.internal-auth.mode", "oauth2");
    application.setDefaultProperties(settings);
    var credentials =
        new ModuleServiceCredentials(
            Map.of("bootstrap-one", () -> "bootstrap-one", "bootstrap-two", () -> "bootstrap-two"));
    application.addInitializers(
        context -> {
          var factory = context.getBeanFactory();
          factory.registerSingleton("bootstrapEffects", effects);
          factory.registerSingleton("credentials", credentials);
          factory.registerSingleton(
              "discovery",
              (ServiceDiscovery)
                  capability ->
                      List.of(
                          new ServiceRoute(
                              "registry", "instance", URI.create("http://unused"), Map.of())));
          factory.registerSingleton(
              "remote",
              (RemoteInvoker)
                  (route, request) -> {
                    String scope = (String) request.args().getFirst();
                    String key = scope + ":" + request.args().get(2) + ":" + request.args().get(3);
                    calls.add(credentials.getAccessToken() + ":" + scope + ":" + request.method());
                    if (request.method().startsWith("shouldApply")) {
                      return RemoteResponse.success(!applied.contains(key));
                    }
                    if (request.method().startsWith("markApplied")) {
                      applied.add(key);
                    }
                    return RemoteResponse.success(null);
                  });
        });
    return application.run();
  }

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules
  @ImportAutoConfiguration(PlatformBootstrapAutoConfiguration.class)
  static class Host {}

  @Configuration(proxyBeanMethods = false)
  static class Unowned {
    @Bean
    PlatformBootstrapContribution unownedContribution() {
      return () ->
          BootstrapContribution.versioned(
              "bootstrap-one",
              "data",
              "injected",
              "1",
              1,
              () -> {
                throw new AssertionError("Unowned work must not execute");
              });
    }
  }
}
