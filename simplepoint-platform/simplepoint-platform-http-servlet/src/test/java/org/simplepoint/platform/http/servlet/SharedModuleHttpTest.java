package org.simplepoint.platform.http.servlet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.http.fixture.metadata.MetadataModule;
import org.simplepoint.http.fixture.sharedalpha.SharedAlphaModule;
import org.simplepoint.http.fixture.sharedbeta.SharedBetaModule;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleHttpPlan;
import org.simplepoint.platform.composition.spring.EnablePlatformModules;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SharedModuleHttpTest {
  @TempDir java.nio.file.Path temporary;

  private final WebApplicationContextRunner runner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  WebMvcAutoConfiguration.class, ModuleHttpAutoConfiguration.class))
          .withUserConfiguration(Host.class)
          .withPropertyValues("simplepoint.service-router.internal-auth.mode=oauth2");

  @Test
  void actualControllerAliasesAndInheritedRootsDriveSplitAndCombinedRouting() {
    var catalog = new ModuleCatalog(List.of(SharedAlphaModule.class, SharedBetaModule.class));
    var split =
        catalog.deploy(Map.of("identity", List.of("shared-alpha"), "oidc", List.of("shared-beta")));
    assertThat(split.httpRoutes())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "/common/users",
                "identity",
                "/common/platform/users",
                "identity",
                "/common/oidc/clients",
                "oidc"));
    var together = catalog.deploy(Map.of("combined", List.of("shared-alpha", "shared-beta")));
    assertThat(together.httpRoutes().keySet()).isEqualTo(split.httpRoutes().keySet());
    assertThat(together.httpRoutes().values()).containsOnly("combined");
  }

  @Test
  void combinedMvcMountsSharedPrefixOnceAndRetainsNestedRoutesAndAliases() {
    runner
        .withPropertyValues("simplepoint.modules.active=shared-alpha,shared-beta")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var mvc = MockMvcBuilders.webAppContextSetup(context).build();
              mvc.perform(get("/common/users"))
                  .andExpect(status().isOk())
                  .andExpect(content().string("accounts"));
              mvc.perform(get("/common/platform/users"))
                  .andExpect(status().isOk())
                  .andExpect(content().string("accounts"));
              mvc.perform(get("/common/users/preferences"))
                  .andExpect(status().isOk())
                  .andExpect(content().string("preferences"));
              mvc.perform(get("/common/oidc/clients"))
                  .andExpect(status().isOk())
                  .andExpect(content().string("clients"));
              mvc.perform(get("/users")).andExpect(status().isNotFound());
              mvc.perform(get("/common/common/users")).andExpect(status().isNotFound());
            });
  }

  @Test
  void selectingOneSharedOwnerDoesNotActivateTheOther() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed().doesNotHaveBean(SharedBetaModule.Clients.class);
          var mvc = MockMvcBuilders.webAppContextSetup(context).build();
          mvc.perform(get("/common/users")).andExpect(status().isOk());
          mvc.perform(get("/common/oidc/clients")).andExpect(status().isNotFound());
        });
  }

  @Test
  void siblingAndWildcardCapturesCannotBypassSubtreeOwnership() {
    var plan =
        ModuleHttpPlan.from(
            new ModuleCatalog(List.of(SharedAlphaModule.class, SharedBetaModule.class))
                .plan(List.of("shared-alpha", "shared-beta")));
    assertThatThrownBy(
            () ->
                ModuleHttpMappings.validate(
                    plan, SharedAlphaModule.Accounts.class, Set.of("/common/oidc/clients")))
        .hasMessageContaining("MODULE_HTTP_PREFIX_MISMATCH");
    for (String path : List.of("/common/users", "/common/{name}", "/common/**", "/{*path}")) {
      assertThatThrownBy(() -> ModuleHttpMappings.validate(plan, Unowned.class, Set.of(path)))
          .hasMessageContaining("MODULE_HTTP_NAMESPACE_CAPTURE");
    }
    ModuleHttpMappings.validate(
        plan, Unowned.class, Set.of("/common", "/common/user-settings", "/health", ""));
  }

  @Test
  void dynamicRootsAreBoundedAndNeverResolveEnvironmentOrTraversal() {
    assertThat(HttpRoutePaths.literalRoot("/roles/{roleId}/grants")).isEqualTo("/roles");
    assertThat(HttpRoutePaths.literalRoot("/users/preferences/")).isEqualTo("/users/preferences");
    for (String invalid :
        List.of(
            "/../escape",
            "/users/../escape",
            "/users/{id}/../escape",
            "/${root}/users",
            "/#{root}/users",
            "/users//preferences",
            "users")) {
      assertThatThrownBy(() -> HttpRoutePaths.literalRoot(invalid))
          .hasMessageContaining("INVALID_CONTROLLER_HTTP_ROOT");
    }
    assertThat(HttpRoutePaths.captures("/common/users", "/common/user-settings/{id}")).isFalse();
  }

  @Test
  void composedConditionalControllerMetadataDoesNotInitializeItsClass() {
    var plan = new ModuleCatalog(List.of(MetadataModule.class)).plan(List.of("metadata"));
    assertThat(ModuleHttpPlan.from(plan).routes().get("metadata"))
        .containsExactly("/common/reserved");
  }

  @Test
  void duplicateControllerClassesAreRejectedInsteadOfFollowingClasspathOrder() throws Exception {
    ClassLoader parent = getClass().getClassLoader();
    String controller = SharedAlphaModule.Accounts.class.getName().replace('.', '/') + ".class";
    java.nio.file.Path copy = temporary.resolve(controller);
    java.nio.file.Files.createDirectories(copy.getParent());
    try (var input = parent.getResourceAsStream(controller)) {
      java.nio.file.Files.copy(input, copy);
    }
    try (var loader =
        new java.net.URLClassLoader(new java.net.URL[] {temporary.toUri().toURL()}, parent) {
          Class<?> module() throws java.io.IOException {
            String name = SharedAlphaModule.class.getName();
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
              byte[] bytes = input.readAllBytes();
              return defineClass(name, bytes, 0, bytes.length);
            }
          }
        }) {
      var catalog = new ModuleCatalog(List.of(loader.module()));
      assertThatThrownBy(() -> catalog.plan(List.of("shared-alpha")))
          .hasMessageContaining("DUPLICATE_HTTP_CONTROLLER");
    }
  }

  @Test
  void childModuleCannotReserveControllersLoadedByItsParent() throws Exception {
    var loader =
        new ClassLoader(getClass().getClassLoader()) {
          Class<?> module() throws java.io.IOException {
            String name = SharedAlphaModule.class.getName();
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
              byte[] bytes = input.readAllBytes();
              return defineClass(name, bytes, 0, bytes.length);
            }
          }
        };
    var catalog = new ModuleCatalog(List.of(loader.module()));
    assertThatThrownBy(() -> catalog.plan(List.of("shared-alpha")))
        .hasMessageContaining("MODULE_HTTP_CONTROLLER_LOADER_MISMATCH");
  }

  @Configuration(proxyBeanMethods = false)
  @EnablePlatformModules("shared-alpha")
  static class Host {}

  static class Unowned {}
}
