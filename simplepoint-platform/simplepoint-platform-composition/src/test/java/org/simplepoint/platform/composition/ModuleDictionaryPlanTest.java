package org.simplepoint.platform.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.platform.dictionary.DictionaryContribution;
import org.simplepoint.platform.dictionary.DictionaryDeclaration;
import org.simplepoint.platform.dictionary.DictionaryDefinitions;
import org.simplepoint.platform.dictionary.ModuleDictionaries;

class ModuleDictionaryPlanTest {
  @Test
  void selectedMetadataFollowsModuleAcrossHosts() {
    var catalog = new ModuleCatalog(List.of(First.class, Empty.class));
    assertTrue(ModuleDictionaryPlan.declarations(catalog.plan(List.of("empty"))).isEmpty());
    var one =
        ModuleDictionaryPlan.declarations(catalog.deploy(Map.of("all", List.of("first", "empty"))));
    var split =
        ModuleDictionaryPlan.declarations(
            catalog.deploy(Map.of("a", List.of("first"), "b", List.of("empty"))));
    assertEquals(one, split);
    assertEquals(Map.of("first", new FirstDefinitions().declaration()), one);
    assertThrows(UnsupportedOperationException.class, one::clear);
  }

  @Test
  void rejectsCrossOwnerDuplicateEvenWhenAssignedToDifferentHosts() {
    var catalog = new ModuleCatalog(List.of(First.class, Second.class));
    var local =
        assertThrows(
            ModuleCompositionException.class, () -> catalog.plan(List.of("first", "second")));
    var split =
        assertThrows(
            ModuleCompositionException.class,
            () -> catalog.deploy(Map.of("a", List.of("first"), "b", List.of("second"))));
    assertEquals("DUPLICATE_DICTIONARY_OWNER", local.code());
    assertEquals(local.code(), split.code());
  }

  @Test
  void rejectsOwnerMismatchAndInaccessibleFactory() {
    var mismatch =
        assertThrows(
            ModuleCompositionException.class,
            () -> new ModuleCatalog(List.of(Mismatch.class)).plan(List.of("mismatch")));
    assertEquals("DICTIONARY_MODULE_MISMATCH", mismatch.code());
    var inaccessible =
        assertThrows(
            ModuleCompositionException.class,
            () -> new ModuleCatalog(List.of(Broken.class)).plan(List.of("broken")));
    assertEquals("INVALID_MODULE_DICTIONARIES", inaccessible.code());
  }

  private static DictionaryContribution declaration(String owner) {
    return new DictionaryContribution(
        owner,
        "seed",
        "1",
        300,
        List.of(new DictionaryDeclaration("shared.code", "Name", null, 10, List.of())));
  }

  @PlatformModule(id = "first", packages = "example.first")
  @ModuleDictionaries(FirstDefinitions.class)
  static class First {}

  @PlatformModule(id = "second", packages = "example.second")
  @ModuleDictionaries(SecondDefinitions.class)
  static class Second {}

  @PlatformModule(id = "empty", packages = "example.empty")
  static class Empty {}

  @PlatformModule(id = "mismatch", packages = "example.mismatch")
  @ModuleDictionaries(FirstDefinitions.class)
  static class Mismatch {}

  @PlatformModule(id = "broken", packages = "example.broken")
  @ModuleDictionaries(BrokenDefinitions.class)
  static class Broken {}

  public static class FirstDefinitions implements DictionaryDefinitions {
    public FirstDefinitions() {}

    @Override
    public DictionaryContribution declaration() {
      return ModuleDictionaryPlanTest.declaration("first");
    }
  }

  public static class SecondDefinitions implements DictionaryDefinitions {
    public SecondDefinitions() {}

    @Override
    public DictionaryContribution declaration() {
      return ModuleDictionaryPlanTest.declaration("second");
    }
  }

  public static class BrokenDefinitions implements DictionaryDefinitions {
    private BrokenDefinitions() {}

    @Override
    public DictionaryContribution declaration() {
      return null;
    }
  }
}
