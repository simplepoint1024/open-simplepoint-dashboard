package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.plugin.rbac.resource.api.initialization.ResourceModuleInitializer;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.service.ResourceRegistrationService;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Empty-install and repeated registration use the same declarations and initializer as production.
 */
class PlatformMenuInitializationTest extends ResourceCatalogTestSupport {
  private static final Map<String, String> MODULES = new LinkedHashMap<>();

  static {
    MODULES.put("common-ui", "common");
    MODULES.put("app-http", "app");
    MODULES.put("workflow-http", "workflow");
    MODULES.put("dna-http", "dna");
    MODULES.put("ai-http", "ai");
    MODULES.put("auditing-http", "auditing");
  }

  @Test
  void emptyCatalogAndRepeatedStartupProduceIdenticalStableMenus() throws Exception {
    registerAll();
    var first = snapshot();
    assertThat(first.size()).isGreaterThan(100);
    assertParent("app.workbench.view", "app.platform.view", "app");
    assertParent("workflow.center.view", "app.platform.view", "workflow");
    assertParent("dna.health.view", "navigation.operations.health.view", "dna");
    assertParent("workspace.resources.view", "navigation.resources.view", "common");
    assertParent("workspace.packages.view", "personal.view", "common");
    assertThat(resource("workspace.security.view").getRouteKind()).isEqualTo("hidden");
    registerAll();
    assertThat(snapshot()).isEqualTo(first);
  }

  private void registerAll() throws Exception {
    for (var module : MODULES.entrySet()) {
      var initializer = new ResourceModuleInitializer(mock(ObjectProvider.class));
      initializer
          .prepare(
              new ModuleInitializationContext() {
                @Override
                public String moduleId() {
                  return module.getKey();
                }

                @Override
                public Optional<String> resourceOwner() {
                  return Optional.of(module.getValue());
                }

                @Override
                public byte[] resource(String name) {
                  try {
                    return java.nio.file.Files.readAllBytes(
                        Path.of(System.getProperty("navigation.declaration." + moduleId())));
                  } catch (java.io.IOException error) {
                    throw new IllegalStateException(error);
                  }
                }

                @Override
                public void claimResourceCodes(Set<String> codes) {
                  assertThat(codes).isNotEmpty();
                }

                @Override
                public void requireResourceParents(Map<String, String> parents) {
                  parents.forEach(
                      (code, owner) -> {
                        assertThat(PlatformMenuInitializationTest.this.resource(code).getPluginId())
                            .isEqualTo(owner);
                        assertThat(
                                PlatformMenuInitializationTest.this
                                    .resource(code)
                                    .getSharedDirectory())
                            .isTrue();
                      });
                }

                @Override
                public <T> T client(Class<T> contract) {
                  return contract.cast((ResourceRegistrationService) service::sync);
                }
              })
          .run();
    }
  }

  private Map<String, String> snapshot() {
    return read(
        () -> {
          var result = new java.util.TreeMap<String, String>();
          em.createQuery("from Resource", Resource.class)
              .getResultList()
              .forEach(
                  row ->
                      result.put(
                          row.getCode(),
                          row.getId() + ":" + row.getParentId() + ":" + row.getPluginId()));
          return result;
        });
  }

  private void assertParent(String child, String parent, String owner) {
    var row = resource(child);
    assertThat(row.getParentId()).isEqualTo(resource(parent).getId());
    assertThat(row.getPluginId()).isEqualTo(owner);
  }
}
