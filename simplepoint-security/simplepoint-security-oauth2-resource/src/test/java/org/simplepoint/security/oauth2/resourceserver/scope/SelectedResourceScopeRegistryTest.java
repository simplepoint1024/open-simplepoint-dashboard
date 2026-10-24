package org.simplepoint.security.oauth2.resourceserver.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplepoint.platform.composition.ModuleCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializer;
import org.simplepoint.platform.composition.ModuleResourceOwner;
import org.simplepoint.platform.composition.PlatformModule;
import org.simplepoint.security.entity.ResourceScopeType;

class SelectedResourceScopeRegistryTest {
  @TempDir Path directory;
  private final ModuleCatalog catalog =
      new ModuleCatalog(List.of(One.class, Two.class, Plain.class));

  @Test
  void selectedDeclarationsExcludeLegacyAndUnselectedResourcesIncludingMalformedAssets()
      throws Exception {
    write("META-INF/simplepoint/modules/one/resources.json", document("old-one", "one.view"));
    write("META-INF/simplepoint/modules/two/resources.json", "{broken");
    write("META-INF/simplepoint/resources/unselected.json", "{broken");
    try (var loader = loader(directory)) {
      var selected =
          new ClasspathResourceScopeRegistry(
              new ObjectMapper(), catalog.plan(List.of("one")), loader);
      assertEquals(Map.of("one.view", Set.of(ResourceScopeType.TENANT)), selected.snapshot());
      assertEquals("one.view", selected.findPage("one.view").orElseThrow());
      assertTrue(
          new ClasspathResourceScopeRegistry(
                  new ObjectMapper(), catalog.plan(List.of("plain")), loader)
              .snapshot()
              .isEmpty());
    }
  }

  @Test
  void duplicateCodesAcrossDifferentCompleteOwnersAreNotHiddenByIdenticalScopes() throws Exception {
    write("META-INF/simplepoint/modules/one/resources.json", document("old-one", "same.view"));
    write("META-INF/simplepoint/modules/two/resources.json", document("old-two", "same.view"));
    try (var loader = loader(directory)) {
      assertEquals(
          "DUPLICATE_RESOURCE_CODE",
          assertThrows(
                  ModuleCompositionException.class,
                  () ->
                      new ClasspathResourceScopeRegistry(
                          new ObjectMapper(), catalog.plan(List.of("one", "two")), loader))
              .code());
    }
  }

  @Test
  void selectedMissingMismatchedAndDuplicateAssetsFailClosed() throws Exception {
    try (var loader = loader(directory)) {
      assertEquals(
          "MISSING_MODULE_ASSET",
          assertThrows(
                  ModuleCompositionException.class,
                  () ->
                      new ClasspathResourceScopeRegistry(
                          new ObjectMapper(), catalog.plan(List.of("one")), loader))
              .code());
    }
    write("META-INF/simplepoint/modules/one/resources.json", document("wrong-owner", "one.view"));
    try (var loader = loader(directory)) {
      assertEquals(
          "INVALID_RESOURCE_DECLARATION",
          assertThrows(
                  ModuleCompositionException.class,
                  () ->
                      new ClasspathResourceScopeRegistry(
                          new ObjectMapper(), catalog.plan(List.of("one")), loader))
              .code());
    }
    Path second = Files.createDirectory(directory.resolve("other-artifact"));
    Path duplicate = second.resolve("META-INF/simplepoint/modules/one/resources.json");
    Files.createDirectories(duplicate.getParent());
    Files.writeString(duplicate, document("old-one", "one.view"));
    try (var loader = loader(directory, second)) {
      assertEquals(
          "DUPLICATE_MODULE_ASSET",
          assertThrows(
                  ModuleCompositionException.class,
                  () ->
                      new ClasspathResourceScopeRegistry(
                          new ObjectMapper(), catalog.plan(List.of("one")), loader))
              .code());
    }
  }

  @Test
  void legacyHostsContinueLoadingBothHistoricalAndMovedDeclarations() throws Exception {
    write("META-INF/simplepoint/modules/one/resources.json", document("old-one", "one.view"));
    write("META-INF/simplepoint/resources/legacy.json", document("legacy", "legacy.view"));
    try (var loader = loader(directory)) {
      var registry = new ClasspathResourceScopeRegistry(new ObjectMapper(), null, loader);
      assertTrue(registry.findScopes("one.view").isPresent());
      assertTrue(registry.findScopes("legacy.view").isPresent());
    }
  }

  private void write(String path, String content) throws Exception {
    Path file = directory.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private URLClassLoader loader(Path... roots) throws Exception {
    var urls = new java.util.ArrayList<URL>();
    for (Path root : roots) {
      urls.add(root.toUri().toURL());
    }
    return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
  }

  private static String document(String owner, String code) {
    return "{\"module\":\""
        + owner
        + "\",\"resources\":[{\"code\":\""
        + code
        + "\",\"type\":\"PAGE\",\"scopeTypes\":[\"TENANT\"]}]}";
  }

  @ModuleInitialization(registersResources = true)
  static class Registrar implements ModuleInitializer {
    @Override
    public Runnable prepare(ModuleInitializationContext context) {
      return () -> {};
    }
  }

  @PlatformModule(id = "one", packages = "fixture.one", initializers = Registrar.class)
  @ModuleResourceOwner("old-one")
  static class One {}

  @PlatformModule(id = "two", packages = "fixture.two", initializers = Registrar.class)
  @ModuleResourceOwner("old-two")
  static class Two {}

  @PlatformModule(id = "plain", packages = "fixture.plain")
  static class Plain {}
}
