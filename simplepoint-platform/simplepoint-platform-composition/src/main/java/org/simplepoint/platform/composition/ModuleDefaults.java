package org.simplepoint.platform.composition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Loads bounded, non-secret platform defaults contributed by selected modules. */
public final class ModuleDefaults {

  private ModuleDefaults() {}

  /**
   * Loads optional META-INF/simplepoint/modules/{id}.properties resources.
   * Conflicting defaults are rejected instead of depending on classpath or dependency order.
   *
   * @param plan selected modules
   * @param loader trusted application class loader
   * @return immutable platform property defaults
   */
  public static Map<String, String> load(final ModulePlan plan, final ClassLoader loader) {
    var defaults = new TreeMap<String, String>();
    for (var module : plan.modules()) {
      String name = "META-INF/simplepoint/modules/" + module.id() + ".properties";
      try {
        var resources = Collections.list(loader.getResources(name));
        if (resources.size() > 1) {
          throw failure("DUPLICATE_MODULE_DEFAULTS", module.id());
        }
        if (resources.isEmpty()) {
          continue;
        }
        byte[] bytes;
        try (var input = resources.getFirst().openStream()) {
          bytes = input.readNBytes(65_537);
        }
        if (bytes.length > 65_536) {
          throw failure("MODULE_DEFAULTS_LIMIT", module.id());
        }
        var properties = new Properties() {
          @Override
          public synchronized Object put(final Object key, final Object value) {
            if (containsKey(key)) {
              throw failure("DUPLICATE_MODULE_DEFAULT_KEY", module.id() + ": " + key);
            }
            return super.put(key, value);
          }
        };
        properties.load(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        if (properties.size() > 256) {
          throw failure("MODULE_DEFAULTS_LIMIT", module.id());
        }
        for (String key : properties.stringPropertyNames()) {
          if (!key.matches("simplepoint\\.[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw failure("INVALID_MODULE_DEFAULT_KEY", module.id() + ": " + key);
          }
          String value = properties.getProperty(key);
          String previous = defaults.putIfAbsent(key, value);
          if (previous != null && !previous.equals(value)) {
            throw failure("CONFLICTING_MODULE_DEFAULT", key);
          }
        }
      } catch (IOException | IllegalArgumentException ex) {
        if (ex instanceof ModuleCompositionException composition) {
          throw composition;
        }
        throw failure("MODULE_DEFAULTS_LOAD_FAILED", module.id());
      }
    }
    return Collections.unmodifiableMap(defaults);
  }

  private static ModuleCompositionException failure(final String code, final String detail) {
    return new ModuleCompositionException(code, detail);
  }
}
