package org.simplepoint.platform.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.simplepoint.http.fixture.alpha.AlphaController;
import org.simplepoint.http.fixture.beta.BetaController;
import org.simplepoint.platform.composition.spring.EnablePlatformModules;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class ModuleHttpAssemblyTest {
  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
          .withPropertyValues("simplepoint.service-router.internal-auth.mode=oauth2");

  @Test
  void actualModuleSelectionSeparatesIdenticalRoutesAndLeavesUnselectedControllersAbsent() {
    runner
        .withUserConfiguration(Host.class)
        .withPropertyValues("simplepoint.modules.active=alpha,beta")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(AlphaController.class)
                  .hasSingleBean(BetaController.class);
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/alpha/records"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.module").value("alpha"));
              mvc.perform(get("/beta/records"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.module").value("beta"));
              mvc.perform(get("/records")).andExpect(status().isNotFound());
              mvc.perform(get("/domain/records")).andExpect(status().isNotFound());
            });
    runner
        .withUserConfiguration(Host.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean(BetaController.class);
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/alpha/records")).andExpect(status().isOk());
              mvc.perform(get("/beta/records")).andExpect(status().isNotFound());
            });
  }

  @Test
  void everyHandlerMappingReceivesTheSamePrefixIncludingAdditionalPlatformMappings() {
    runner
        .withUserConfiguration(Host.class, AdditionalMapping.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(RequestMappingHandlerMapping.class)).hasSize(2);
              context
                  .getBeansOfType(RequestMappingHandlerMapping.class)
                  .values()
                  .forEach(
                      mapping -> {
                        assertThat(
                                mapping.getHandlerMethods().entrySet().stream()
                                    .filter(
                                        entry ->
                                            entry.getValue().getBeanType() == AlphaController.class)
                                    .flatMap(entry -> entry.getKey().getPatternValues().stream())
                                    .toList())
                            .containsExactly("/alpha/records");
                        assertThat(
                                mapping.getHandlerMethods().keySet().stream()
                                    .flatMap(route -> route.getPatternValues().stream())
                                    .toList())
                            .contains("/_simplepoint/service-router/invoke")
                            .doesNotContain("/records");
                      });
            });
  }

  @Test
  void legacyHostKeepsGatewayStrippedRoutesAndDoesNotStartModularHosting() {
    runner
        .withUserConfiguration(LegacyHost.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed().doesNotHaveBean("moduleHttpHandlerMappings");
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/records")).andExpect(status().isOk());
              mvc.perform(get("/alpha/records")).andExpect(status().isNotFound());
            });
  }

  @Test
  void undeclaredDomainControllersCannotAccidentallyBecomePublicHttpEndpoints() {
    runner
        .withUserConfiguration(Host.class)
        .withPropertyValues("simplepoint.modules.active=domain")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("UNDECLARED_MODULE_HTTP"));
  }

  @Test
  void unownedControllerCannotCaptureAnOwnedNamespace() {
    runner
        .withUserConfiguration(Host.class, Capture.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("MODULE_HTTP_NAMESPACE_CAPTURE"));
  }

  @Test
  void pluginShadowCannotBorrowCoreModuleOwnership() throws Exception {
    var plan =
        org.simplepoint.platform.composition.ModuleHttpPlan.from(
            org.simplepoint.platform.composition.ModuleCatalog.discover(getClass().getClassLoader())
                .plan(java.util.List.of("alpha")));
    Class<?> shadow =
        new ClassLoader(AlphaController.class.getClassLoader()) {
          Class<?> isolate() throws java.io.IOException {
            String name = AlphaController.class.getName();
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
              byte[] bytes = input.readAllBytes();
              return defineClass(name, bytes, 0, bytes.length);
            }
          }
        }.isolate();
    assertThat(plan.ownerOf(AlphaController.class)).contains("alpha");
    assertThat(plan.ownerOf(shadow)).isEmpty();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> ModuleHttpMappings.validate(plan, shadow, java.util.Set.of("/alpha/records")))
        .hasMessageContaining("MODULE_HTTP_NAMESPACE_CAPTURE");
  }

  @Test
  void competingPrefixConfigurerCannotSilentlyMoveThePublicApi() {
    runner
        .withUserConfiguration(Host.class, WrongPrefix.class)
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasStackTraceContaining("MODULE_HTTP_PREFIX_MISMATCH"));
  }

  @Configuration(proxyBeanMethods = false)
  static class WrongPrefix {
    @Bean
    RequestMappingHandlerMapping wrongPrefixMapping() {
      var mapping = new RequestMappingHandlerMapping();
      mapping.setPathPrefixes(java.util.Map.of("/unexpected", type -> true));
      return mapping;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules("alpha")
  static class Host {}

  @Configuration(proxyBeanMethods = false)
  @Import(AlphaController.class)
  static class LegacyHost {}

  @Configuration(proxyBeanMethods = false)
  static class AdditionalMapping {
    @Bean
    RequestMappingHandlerMapping additionalMapping() {
      return new RequestMappingHandlerMapping();
    }
  }

  @org.springframework.web.bind.annotation.RestController
  static class Capture {
    @org.springframework.web.bind.annotation.GetMapping("/alpha/stolen")
    public String capture() {
      return "unexpected";
    }
  }
}
