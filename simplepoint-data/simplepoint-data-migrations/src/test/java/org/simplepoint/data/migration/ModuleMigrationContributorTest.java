package org.simplepoint.data.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.migrationfixture.one.OneModule;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleMigrations;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleSharedSchemas;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.simplepoint.platform.composition.PlatformModule;

class ModuleMigrationContributorTest {
  private final ModulePlan modules =
      new ModuleCatalog(List.of(OneModule.class)).plan(List.of("one"));
  private final ModuleStorageBindings bindings =
      new ModuleStorageBindings(
          Map.of(
              "one",
              new ModuleStorageBindings.Binding("default", "source", "emf", "em", "tx", "jdbc")));
  private final ModuleMigrationPlan domain = ModuleMigrationPlan.discover(modules, bindings);

  @Test
  void sharedSchemaDoesNotEraseMissingDomainDeclarationsAndCannotMutateTheOriginalPlan() {
    var incomplete = new ModuleMigrationPlan(domain.schemas(), List.of("unresolved"));
    var result =
        ModuleMigrationPlan.contribute(
            modules,
            bindings,
            incomplete,
            List.of((selected, storage) -> List.of(schema("two", "default"))));
    assertEquals(
        List.of("one", "two"),
        result.schemas().stream().map(ModuleMigrationPlan.ModuleSchema::module).toList());
    assertEquals(List.of("unresolved"), result.undeclaredModules());
    assertEquals(1, domain.schemas().size());
    assertThrows(UnsupportedOperationException.class, () -> result.schemas().clear());
  }

  @Test
  void sharedContributorsCannotImpersonateSelectedModulesOrWriteToUnselectedStores() {
    for (var invalid : List.of(schema("one", "default"), schema("two", "outside"))) {
      assertEquals(
          "INVALID_SHARED_MIGRATION_OWNER",
          assertThrows(
                  ModuleCompositionException.class,
                  () ->
                      ModuleMigrationPlan.contribute(
                          modules,
                          bindings,
                          domain,
                          List.of((selected, storage) -> List.of(invalid))))
              .code());
    }
  }

  @Test
  void duplicateSharedOwnersAndOverlappingDomainTablesRemainErrors() {
    var shared = schema("two", "default");
    assertEquals(
        "DUPLICATE_MODULE_MIGRATION",
        assertThrows(
                ModuleCompositionException.class,
                () ->
                    ModuleMigrationPlan.contribute(
                        modules,
                        bindings,
                        domain,
                        List.of((selected, storage) -> List.of(shared, shared))))
            .code());
    var overlap =
        new ModuleMigrationPlan.ModuleSchema(
            shared.module(),
            shared.store(),
            shared.historyTable(),
            domain.schemas().getFirst().tables(),
            shared.scripts());
    assertEquals(
        "DUPLICATE_MIGRATION_TABLE_OWNER",
        assertThrows(
                ModuleCompositionException.class,
                () ->
                    ModuleMigrationPlan.contribute(
                        modules,
                        bindings,
                        domain,
                        List.of((selected, storage) -> List.of(overlap))))
            .code());
  }

  private ModuleMigrationPlan.ModuleSchema schema(String owner, String store) {
    return ModuleMigrationPlan.loadSchema(owner, store, getClass().getClassLoader());
  }

  @Test
  void declaredInfrastructureCannotSilentlyDisappearWhenItsProviderIsMissing() {
    var required = new ModuleCatalog(List.of(RequiresShared.class)).plan(List.of("one"));
    assertEquals(
        "MISSING_SHARED_MIGRATION_CONTRIBUTOR",
        assertThrows(
                ModuleCompositionException.class,
                () -> ModuleMigrationPlan.contribute(required, bindings, domain, List.of()))
            .code());
    var result =
        ModuleMigrationPlan.contribute(
            required,
            bindings,
            domain,
            List.of((selected, storage) -> List.of(schema("two", "default"))));
    assertEquals(2, result.schemas().size());
  }

  @ModuleSharedSchemas("two")
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @interface NeedsShared {}

  @PlatformModule(id = "one", packages = "fixture.shared.required")
  @ModuleMigrations
  @NeedsShared
  static class RequiresShared {}
}
