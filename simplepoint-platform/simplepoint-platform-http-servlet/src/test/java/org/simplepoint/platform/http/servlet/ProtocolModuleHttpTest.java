package org.simplepoint.platform.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.http.fixture.alpha.AlphaModule;
import org.simplepoint.http.fixture.protocol.ProtocolModule;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.spring.EnablePlatformModules;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProtocolModuleHttpTest {
  @Test
  void exactProtocolRootsCoexistWithPrefixedDomainsInEveryHostPlan() {
    var catalog = new ModuleCatalog(List.of(AlphaModule.class, ProtocolModule.class));
    var together = catalog.deploy(Map.of("one", List.of("alpha", "protocol")));
    var split = catalog.deploy(Map.of("one", List.of("alpha"), "two", List.of("protocol")));
    assertThat(together.httpRoutes().keySet())
        .isEqualTo(split.httpRoutes().keySet())
        .containsExactlyInAnyOrder(
            "/alpha", "/mcp", "/.well-known/oauth-protected-resource/example");
    var modules = together.hosts().get("one");
    var plan = ModuleHttpPlan.from(modules);
    assertThat(ModuleHttpPaths.prefix(modules, ProtocolModule.Metadata.class)).isEmpty();
    assertThat(ModuleHttpPaths.roots(modules, ProtocolModule.Metadata.class))
        .containsExactlyInAnyOrder("/mcp", "/.well-known/oauth-protected-resource/example");
    for (String path : List.of("/mcp/{code}", "/.well-known/{*path}", "/{*all}")) {
      assertThatThrownBy(() -> ModuleHttpMappings.validate(plan, getClass(), Set.of(path)))
          .hasMessageContaining("MODULE_HTTP_NAMESPACE_CAPTURE");
    }
    ModuleHttpMappings.validate(plan, getClass(), Set.of("/actuator/health", "/other"));
    assertThatThrownBy(() -> ModuleHttpPaths.roots(modules, getClass()))
        .hasMessageContaining("UNOWNED_HTTP_ADAPTER");
  }

  @Test
  void actualMvcKeepsProtocolAddressesAndStillPrefixesBusinessControllers() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
        .withUserConfiguration(Host.class)
        .withPropertyValues("simplepoint.service-router.internal-auth.mode=oauth2")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/.well-known/oauth-protected-resource/example/demo"))
                  .andExpect(status().isOk());
              mvc.perform(get("/mcp/demo")).andExpect(status().isOk());
              mvc.perform(get("/alpha/records")).andExpect(status().isOk());
              mvc.perform(get("/protocol/mcp/demo")).andExpect(status().isNotFound());
              mvc.perform(get("/records")).andExpect(status().isNotFound());
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules({"alpha", "protocol"})
  static class Host {}
}
