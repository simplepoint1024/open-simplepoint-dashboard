package org.simplepoint.platform.composition.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.composition.fixture.api.Greeting;
import org.simplepoint.composition.fixture.second.SecondModule.Consumer;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleDescriptor;
import org.simplepoint.platform.composition.ModulePlan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class PlatformModuleAssemblyTest {

  @Test
  void selectionActivatesDependencyClosureAndUsesTheRoutingBoundaryForLocalCalls() {
    try (var context = contextWithRoots("second")) {
      context.register(Host.class);
      context.refresh();
      ModulePlan plan = context.getBean(ModulePlan.class);
      Greeting greeting = context.getBean(Consumer.class).greeting();

      assertEquals(
          List.of("first", "second"), plan.modules().stream().map(ModuleDescriptor::id).toList());
      assertTrue(Proxy.isProxyClass(greeting.getClass()));
      assertEquals(
          "true",
          context
              .getEnvironment()
              .getProperty("simplepoint.service-router.consumer.local-isolation"));
      assertEquals("Hello Ada", greeting.greet("Ada"));
      assertFalse(context.containsBean("mustNotStart"));
      assertFalse(context.containsBean("unannouncedProvider"));
    }
  }

  @Test
  void indexedEnvironmentSelectionOverridesCompiledDefaults() {
    try (var context = contextWithRoots("first")) {
      context.register(Host.class);
      context.refresh();

      assertEquals(
          List.of("first"),
          context.getBean(ModulePlan.class).modules().stream().map(ModuleDescriptor::id).toList());
      assertFalse(context.containsBean("consumer"));
    }
  }

  @Test
  void remoteConsumerHasProxyWithoutLocalProvider() {
    try (var context = contextWithRoots("remote")) {
      context.register(Host.class);
      context.refresh();

      var plan = context.getBean(ModulePlan.class);
      assertEquals(Map.of("greeting:1", Greeting.class), plan.remoteCapabilities());
      assertTrue(plan.capabilityOwners().isEmpty());
      assertTrue(
          Proxy.isProxyClass(
              context
                  .getBean(org.simplepoint.composition.fixture.remote.RemoteModule.Consumer.class)
                  .greeting()
                  .getClass()));
    }
  }

  @Test
  void missingDeclaredProviderFailsStartup() {
    assertStartupFailure("missing", "MISSING_LOCAL_PROVIDER");
  }

  @Test
  void undeclaredProviderFailsStartup() {
    assertStartupFailure("undeclared", "UNDECLARED_CAPABILITY");
  }

  @Test
  void unknownModuleFailsStartupBeforeActivatingComponents() {
    assertStartupFailure("unknown", "MISSING_MODULE");
  }

  @Test
  void emptyHostSelectionFailsInsteadOfStartingAnEmptyService() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(EmptyHost.class);
      assertFailure("NO_ACTIVE_MODULES", context::refresh);
    }
  }

  @Test
  void moduleDefaultsNeverOverrideExplicitDeploymentPolicy() {
    try (var context = contextWithRoots("first")) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "deployment-policy", Map.of("simplepoint.fixture.first.enabled", "false")));
      context.register(Host.class);

      assertFailure("MISSING_LOCAL_PROVIDER", context::refresh);
      assertEquals(
          "false", context.getEnvironment().getProperty("simplepoint.fixture.first.enabled"));
    }
  }

  @Test
  void modularHostsCannotDisableTheServiceCallBoundary() {
    try (var context = contextWithRoots("first")) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "unsafe-boundary",
                  Map.of("simplepoint.service-router.consumer.local-isolation", false)));
      context.register(Host.class);
      assertFailure("MODULE_CALL_ISOLATION_REQUIRED", context::refresh);
    }
  }

  @Test
  void domainScanLeavesAutoConfigurationSelectionToBoot() {
    try (var context = contextWithRoots("first")) {
      context.register(Host.class);
      context.refresh();
      assertTrue(!context.containsBean("fixtureInfrastructure"));
    }
    try (var context = contextWithRoots("first")) {
      context.register(Host.class, ExplicitInfrastructure.class);
      context.refresh();
      assertEquals("auto-configuration", context.getBean("fixtureInfrastructure"));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @org.springframework.boot.autoconfigure.ImportAutoConfiguration(
      org.simplepoint.composition.fixture.first.FixtureAutoConfiguration.class)
  static class ExplicitInfrastructure {}

  private static void assertStartupFailure(final String root, final String code) {
    try (var context = contextWithRoots(root)) {
      context.register(Host.class);
      assertFailure(code, context::refresh);
    }
  }

  private static void assertFailure(final String code, final Runnable action) {
    Throwable failure = assertThrows(RuntimeException.class, action::run);
    while (!(failure instanceof ModuleCompositionException) && failure.getCause() != null) {
      failure = failure.getCause();
    }
    assertTrue(failure instanceof ModuleCompositionException, failure.toString());
    assertEquals(code, ((ModuleCompositionException) failure).code());
  }

  private static AnnotationConfigApplicationContext contextWithRoots(final String root) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "module-selection",
                Map.of(
                    "simplepoint.modules.active[0]",
                    root,
                    "simplepoint.service-router.internal-auth.mode",
                    "oauth2",
                    "simplepoint.service-router.internal-auth.oauth2.token-uri",
                    "https://issuer.example/token",
                    "simplepoint.modules.credentials.second.client-secret",
                    "fixture-only",
                    "simplepoint.modules.credentials.remote.client-secret",
                    "fixture-only")));
    return context;
  }

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules("second")
  static class Host {}

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules
  static class EmptyHost {}
}
