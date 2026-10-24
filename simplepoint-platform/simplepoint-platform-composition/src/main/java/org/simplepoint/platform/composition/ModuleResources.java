package org.simplepoint.platform.composition;

import java.io.IOException;
import java.util.Collections;

/**
 * Loads only explicitly addressed module assets; never chooses the first duplicate on a classpath.
 */
public final class ModuleResources {
  private static final int MAX_BYTES = 2 * 1024 * 1024;

  private ModuleResources() {}

  /** Reads an exact required declaration, with path and size bounds. */
  public static byte[] read(String moduleId, ClassLoader loader, String relativePath) {
    if (moduleId == null
        || !moduleId.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
        || relativePath == null
        || relativePath.length() > 512
        || !relativePath.matches("[a-zA-Z0-9_-][a-zA-Z0-9_./-]*")
        || java.util.Arrays.stream(relativePath.split("/", -1))
            .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
      throw new ModuleCompositionException("INVALID_MODULE_ASSET", String.valueOf(moduleId));
    }
    String path = "META-INF/simplepoint/modules/" + moduleId + "/" + relativePath;
    try {
      var resources = Collections.list(loader.getResources(path));
      if (resources.size() != 1) {
        throw new ModuleCompositionException(
            resources.isEmpty() ? "MISSING_MODULE_ASSET" : "DUPLICATE_MODULE_ASSET", path);
      }
      try (var input = resources.getFirst().openStream()) {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
          throw new ModuleCompositionException("MODULE_ASSET_LIMIT", path);
        }
        return bytes;
      }
    } catch (IOException ex) {
      throw new ModuleCompositionException("MODULE_ASSET_READ_FAILED", path);
    }
  }
}
