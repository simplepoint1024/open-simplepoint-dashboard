package org.simplepoint.plugin.i18n.api.initialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializer;
import org.simplepoint.plugin.i18n.api.service.I18nMessageRegistrationService;

/** Registers only the exact locale declarations selected by the owning module's index. */
@ModuleInitialization(consumes = I18nMessageRegistrationService.class)
public final class I18nModuleInitializer implements ModuleInitializer {
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  @Override
  public Runnable prepare(ModuleInitializationContext context) throws Exception {
    var messages = read(context.moduleId(), context::resource);
    var client = context.client(I18nMessageRegistrationService.class);
    return () -> I18nDeclarationRegistration.register(messages, client);
  }

  /** Reads the same selected declarations for the local i18n owner's bootstrap repair. */
  public static Map<String, Map<String, Map<String, String>>> readDeclarations(
      String moduleId, ClassLoader loader) throws Exception {
    return read(
        moduleId,
        path -> org.simplepoint.platform.composition.ModuleResources.read(moduleId, loader, path));
  }

  private static Map<String, Map<String, Map<String, String>>> read(
      String moduleId, java.util.function.Function<String, byte[]> resources) throws Exception {
    var paths = new LinkedHashSet<String>();
    String index = new String(resources.apply("i18n.imports"), StandardCharsets.UTF_8);
    for (String line : index.lines().toList()) {
      String path = line.split("#", 2)[0].strip();
      if (path.isEmpty()) {
        continue;
      }
      if (paths.size() >= 512
          || !path.matches("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*/[a-zA-Z0-9_-][a-zA-Z0-9_.-]*\\.json")
          || !paths.add(path)) {
        throw new ModuleCompositionException("INVALID_I18N_INDEX", moduleId);
      }
    }
    if (paths.isEmpty()) {
      throw new ModuleCompositionException("EMPTY_I18N_INDEX", moduleId);
    }
    Map<String, Map<String, Map<String, String>>> messages = new TreeMap<>();
    for (String path : paths) {
      var node = MAPPER.readTree(resources.apply("i18n/" + path));
      if (node == null || !node.isObject()) {
        throw new ModuleCompositionException("INVALID_I18N_DECLARATION", moduleId + ":" + path);
      }
      var entries = new TreeMap<String, String>();
      for (var entry : node.properties()) {
        if (entry.getKey().isBlank() || !entry.getValue().isTextual()) {
          throw new ModuleCompositionException("INVALID_I18N_MESSAGE", moduleId + ":" + path);
        }
        entries.put(entry.getKey(), entry.getValue().textValue());
      }
      String[] parts = path.split("/");
      messages
          .computeIfAbsent(parts[0], ignored -> new TreeMap<>())
          .put(parts[1].substring(0, parts[1].length() - 5), Map.copyOf(entries));
    }
    return messages;
  }
}
