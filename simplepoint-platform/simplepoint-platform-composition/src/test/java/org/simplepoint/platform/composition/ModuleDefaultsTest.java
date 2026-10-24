package org.simplepoint.platform.composition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleDefaultsTest {

  @TempDir
  Path temporary;

  private final ModuleCatalog catalog = new ModuleCatalog(List.of(First.class, Second.class));

  @Test
  void defaultsLoadOnlyForSelectedModulesAndRemainImmutable() throws Exception {
    write("first", "simplepoint.feature.enabled=true\nsimplepoint.feature.label=设置\n");
    write("second", "simplepoint.other.enabled=true\n");
    try (var loader = loader(temporary)) {
      var defaults = ModuleDefaults.load(catalog.plan(List.of("first")), loader);
      assertEquals(Map.of("simplepoint.feature.enabled", "true", "simplepoint.feature.label", "设置"),
          defaults);
      assertThrows(UnsupportedOperationException.class, defaults::clear);
    }
  }

  @Test
  void conflictsNeverDependOnModuleOrderOrExposeValues() throws Exception {
    write("first", "simplepoint.feature.enabled=first-value\n");
    write("second", "simplepoint.feature.enabled=second-value\n");
    try (var loader = loader(temporary)) {
      var error = assertThrows(ModuleCompositionException.class,
          () -> ModuleDefaults.load(catalog.plan(List.of("second", "first")), loader));
      assertEquals("CONFLICTING_MODULE_DEFAULT", error.code());
      assertEquals("CONFLICTING_MODULE_DEFAULT: simplepoint.feature.enabled", error.getMessage());
    }
  }

  @Test
  void duplicatedResourcesAndKeysAreRejected() throws Exception {
    write("first", "simplepoint.feature.enabled=true\nsimplepoint.feature.enabled=false");
    try (var loader = loader(temporary)) {
      assertCode("DUPLICATE_MODULE_DEFAULT_KEY", loader);
    }
    Path second = temporary.resolve("second");
    Path duplicate = second.resolve("META-INF/simplepoint/modules/first.properties");
    Files.createDirectories(duplicate.getParent());
    Files.writeString(duplicate, "simplepoint.feature.enabled=true");
    try (var loader = loader(temporary, second)) {
      assertCode("DUPLICATE_MODULE_DEFAULTS", loader);
    }
  }

  @Test
  void hostSettingsAndOversizedDefaultsCannotBeImported() throws Exception {
    write("first", "server.port=8080");
    try (var loader = loader(temporary)) {
      assertCode("INVALID_MODULE_DEFAULT_KEY", loader);
      write("first", "#".repeat(65_537));
      assertCode("MODULE_DEFAULTS_LIMIT", loader);
    }
  }

  private void assertCode(final String code, final ClassLoader loader) {
    var error = assertThrows(ModuleCompositionException.class,
        () -> ModuleDefaults.load(catalog.plan(List.of("first")), loader));
    assertEquals(code, error.code());
  }

  private void write(final String id, final String properties) throws Exception {
    Path file = temporary.resolve("META-INF/simplepoint/modules/" + id + ".properties");
    Files.createDirectories(file.getParent());
    Files.writeString(file, properties);
  }

  private URLClassLoader loader(final Path... paths) throws Exception {
    var urls = new java.net.URL[paths.length];
    for (int i = 0; i < paths.length; i++) {
      urls[i] = paths[i].toUri().toURL();
    }
    return new URLClassLoader(urls, getClass().getClassLoader());
  }

  @PlatformModule(id = "first", packages = "example.first")
  static class First {}

  @PlatformModule(id = "second", packages = "example.second")
  static class Second {}
}
