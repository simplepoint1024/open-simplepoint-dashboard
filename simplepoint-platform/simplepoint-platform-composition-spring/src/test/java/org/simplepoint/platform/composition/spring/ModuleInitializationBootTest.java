package org.simplepoint.platform.composition.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplepoint.service.router.invocation.RemoteInvoker;
import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.routing.ServiceDiscovery;
import com.simplepoint.service.router.routing.ServiceRoute;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.plugin.i18n.api.configuration.I18nModuleMessageAutoConfiguration;
import org.simplepoint.plugin.rbac.resource.api.configuration.ResourceAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Exercises real Boot runner order and auto-configuration suppression without external services.
 */
class ModuleInitializationBootTest {
  @Test
  void bootRegistersOnlySelectedAssetsWithEachOwnersCredential() {
    var calls = new ArrayList<Invocation>();
    try (var context = start("startup-one,startup-two", calls, false)) {
      assertThat(context.getBeansOfType(ResourceAutoConfiguration.class)).isEmpty();
      assertThat(context.getBeansOfType(I18nModuleMessageAutoConfiguration.class)).isEmpty();
      assertThat(context.getBean(ModulePlan.class).remoteCapabilities().keySet())
          .containsExactly("i18n.message-registration:2", "security.resource:4");
      assertThat(calls).hasSize(8);
      assertThat(calls.subList(0, 4)).allMatch(call -> call.token().equals("startup-one"));
      assertThat(calls.subList(4, 8)).allMatch(call -> call.token().equals("startup-two"));
      assertThat(
              calls.stream()
                  .filter(call -> call.request().service().equals("security.resource"))
                  .map(call -> call.request().args().getFirst())
                  .toList())
          .containsExactly("historic-one", "startup-two");
      assertThatThrownBy(context.getBean(ModuleServiceCredentials.class)::getAccessToken)
          .hasMessageContaining("UNBOUND_MODULE_CALLER");
      var probe =
          context.getBean(
              org.simplepoint.composition.fixture.startupone.StartupOneModule.Probe.class);
      assertThatThrownBy(
              () ->
                  probe
                      .context()
                      .client(org.simplepoint.security.service.ResourceRegistrationService.class))
          .hasMessageContaining("UNDECLARED_INITIALIZER_CLIENT");
    }
  }

  @Test
  void selectingOneModuleDoesNotReadOtherModulesBrokenAssetsOrRegisterTheirContents() {
    var calls = new ArrayList<Invocation>();
    try (var ignored = start("startup-one", calls, false)) {
      assertThat(calls).hasSize(4).allMatch(call -> call.token().equals("startup-one"));
      assertThat(calls.toString()).doesNotContain("two message", "broken.view");
    }
  }

  @Test
  void missingSelectedDeclarationFailsBootBeforeAnyRemoteReadsOrWrites() {
    var calls = new ArrayList<Invocation>();
    assertThatThrownBy(() -> start("startup-one,startup-zbroken", calls, false))
        .hasStackTraceContaining("MISSING_MODULE_ASSET");
    assertThat(calls).isEmpty();
  }

  @Test
  void requiredRegistryFailureFailsBootWithoutReplayOrCredentialLeak() {
    var calls = new ArrayList<Invocation>();
    assertThatThrownBy(() -> start("startup-one", calls, true))
        .hasStackTraceContaining("registry unavailable");
    assertThat(calls).hasSize(1);
  }

  @Test
  void duplicateCodesAcrossOwnersFailBeforeRemoteEffectsWithoutWebServer() {
    var calls = new ArrayList<Invocation>();
    assertThatThrownBy(() -> start("startup-one,startup-duplicate", calls, false))
        .hasStackTraceContaining("DUPLICATE_RESOURCE_CODE");
    assertThat(calls).isEmpty();
  }

  private ConfigurableApplicationContext start(String roots, List<Invocation> calls, boolean fail) {
    var credentials =
        new ModuleServiceCredentials(
            Map.of(
                "startup-one", () -> "startup-one",
                "startup-two", () -> "startup-two",
                "startup-zbroken", () -> "startup-zbroken",
                "startup-duplicate", () -> "startup-duplicate"));
    var application = new SpringApplication(Host.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.setRegisterShutdownHook(false);
    application.setLogStartupInfo(false);
    application.setDefaultProperties(
        Map.of(
            "spring.main.banner-mode", "off",
            "spring.application.name", "physical-host-must-not-be-owner",
            "simplepoint.modules.active", roots,
            "simplepoint.service-router.internal-auth.mode", "oauth2"));
    application.addInitializers(
        context -> {
          context.getBeanFactory().registerSingleton("fixtureCredentials", credentials);
          context
              .getBeanFactory()
              .registerSingleton(
                  "fixtureDiscovery",
                  (ServiceDiscovery)
                      capability ->
                          List.of(
                              new ServiceRoute(
                                  "registry", "instance", URI.create("http://unused"), Map.of())));
          context
              .getBeanFactory()
              .registerSingleton(
                  "fixtureRemote",
                  (RemoteInvoker)
                      (route, request) -> {
                        calls.add(new Invocation(credentials.getAccessToken(), request));
                        if (fail) {
                          throw new IllegalStateException("registry unavailable");
                        }
                        return RemoteResponse.success(
                            request.method().startsWith("find") ? List.of() : null);
                      });
        });
    return application.run();
  }

  private record Invocation(String token, RemoteRequest request) {}

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules
  @ImportAutoConfiguration({
    ResourceAutoConfiguration.class,
    I18nModuleMessageAutoConfiguration.class
  })
  static class Host {}
}
