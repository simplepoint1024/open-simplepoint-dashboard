package org.simplepoint.platform.composition;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleResourcesTest {
  @TempDir Path directory;

  @Test
  void rejectsMissingDuplicateAndOversizedAssetsInsteadOfChoosingByClasspathOrder()
      throws Exception {
    Path first = directory.resolve("one");
    Path second = directory.resolve("two");
    var path = "META-INF/simplepoint/modules/example/resources.json";
    Files.createDirectories(first.resolve(path).getParent());
    Files.createDirectories(second.resolve(path).getParent());
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {first.toUri().toURL(), second.toUri().toURL()}, null)) {
      assertCode(
          "MISSING_MODULE_ASSET", () -> ModuleResources.read("example", loader, "resources.json"));
      Files.write(first.resolve(path), new byte[] {1, 2, 3});
      assertArrayEquals(
          new byte[] {1, 2, 3}, ModuleResources.read("example", loader, "resources.json"));
      Files.write(second.resolve(path), new byte[] {4});
      assertCode(
          "DUPLICATE_MODULE_ASSET",
          () -> ModuleResources.read("example", loader, "resources.json"));
      Files.delete(second.resolve(path));
      Files.write(first.resolve(path), new byte[2 * 1024 * 1024 + 1]);
      assertCode(
          "MODULE_ASSET_LIMIT", () -> ModuleResources.read("example", loader, "resources.json"));
    }
  }

  @Test
  void rejectsTraversalAndExternalUrls() {
    for (String path :
        new String[] {
          "../other/file",
          "/absolute",
          "x/../file",
          "https://example/file",
          "x//file",
          "x/",
          "x/./file",
          "x\\file"
        }) {
      assertCode(
          "INVALID_MODULE_ASSET",
          () -> ModuleResources.read("example", getClass().getClassLoader(), path));
    }
    assertCode(
        "INVALID_MODULE_ASSET",
        () -> ModuleResources.read("../other", getClass().getClassLoader(), "resources.json"));
  }

  private static void assertCode(String code, Runnable action) {
    assertEquals(code, assertThrows(ModuleCompositionException.class, action::run).code());
  }
}
