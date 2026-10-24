package org.simplepoint.workflow.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.simplepoint.data.migration.ModuleMigrationPlan;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.composition.PlatformModule;

class FlowableProcessMigrationsTest {
  private final ModuleCatalog catalog =
      new ModuleCatalog(List.of(One.class, Two.class, None.class));

  @Test
  void discoveryUsesOnlySelectedOwnersAndDeduplicatesEachStore() {
    var shared = plan(List.of("one", "two", "none"), false);
    assertEquals(1, shared.schemas().size());
    assertEquals(FlowableProcessMigrations.OWNER, shared.schemas().getFirst().module());
    assertEquals(45, shared.schemas().getFirst().tables().size());
    var split = plan(List.of("one", "two", "none"), true);
    assertEquals(
        List.of("default", "other"),
        split.schemas().stream().map(ModuleMigrationPlan.ModuleSchema::store).toList());
    assertTrue(
        split.schemas().stream()
            .allMatch(s -> s.historyTable().equals(shared.schemas().getFirst().historyTable())));
    assertTrue(plan(List.of("none"), false).schemas().isEmpty());
    assertEquals(shared.schemas(), plan(List.of("one"), false).schemas());
    assertTrue(shared.undeclaredModules().isEmpty());
  }

  @Test
  void immutableBaselinePreservesAllPinnedVendorAssetsInDependencyOrder() throws Exception {
    assertEquals(FlowableProcessMigrations.ENGINE_VERSION, ProcessEngine.VERSION);
    StringBuilder expected =
        new StringBuilder(
            "-- Flowable 8.0.0 PostgreSQL baseline. SPDX-License-Identifier: Apache-2.0\n"
                + "-- Vendor assets are preserved below in dependency order; do not edit a"
                + " released baseline.\n");
    for (String path :
        List.of(
            "org/flowable/common/db/create/flowable.postgres.create.common.sql",
            "org/flowable/idm/db/create/flowable.postgres.create.identity.sql",
            "org/flowable/eventregistry/db/create/flowable.postgres.create.eventregistry.sql",
            "org/flowable/db/create/flowable.postgres.create.engine.sql",
            "org/flowable/db/create/flowable.postgres.create.history.sql")) {
      try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
        assertNotNull(stream, path);
        expected
            .append("\n-- Source: ")
            .append(path)
            .append("\n")
            .append(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    var schema = plan(List.of("one"), false).schemas().getFirst();
    assertEquals(List.of("postgresql"), List.copyOf(schema.scripts().keySet()));
    assertEquals(1, schema.scripts().get("postgresql").size());
    assertEquals(expected.toString(), schema.scripts().get("postgresql").getFirst().sql());
  }

  private ModuleMigrationPlan plan(List<String> selected, boolean split) {
    return ModuleMigrationPlan.discover(
        catalog.plan(selected),
        new ModuleStorageBindings(
            Map.of(
                "one", binding("default"),
                "two", binding(split ? "other" : "default"),
                "none", binding("unused"))));
  }

  private static ModuleStorageBindings.Binding binding(String store) {
    return new ModuleStorageBindings.Binding(
        store, store + "Source", store + "Emf", store + "Em", store + "Tx", store + "Jdbc");
  }

  @PlatformModule(id = "one", packages = "fixture.flowable.one")
  @ModuleMigrations(ownsSchema = false)
  @FlowableProcessSchema
  static class One {}

  @PlatformModule(id = "two", packages = "fixture.flowable.two")
  @ModuleMigrations(ownsSchema = false)
  @FlowableProcessSchema
  static class Two {}

  @PlatformModule(id = "none", packages = "fixture.flowable.none")
  @ModuleMigrations(ownsSchema = false)
  static class None {}
}
