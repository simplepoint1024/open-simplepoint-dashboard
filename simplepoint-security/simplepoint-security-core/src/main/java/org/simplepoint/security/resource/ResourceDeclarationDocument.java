package org.simplepoint.security.resource;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import org.simplepoint.platform.composition.ModuleCompositionException;

/** Shared strict parser for selected startup registration and request-scope declarations. */
public record ResourceDeclarationDocument(JsonNode resources, java.util.Set<String> codes) {
  private static final ObjectMapper MAPPER =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(64).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** Keeps the validated identity set independent of parser mutation. */
  public ResourceDeclarationDocument {
    codes = java.util.Set.copyOf(codes);
  }

  /** Reads one bounded complete declaration without trusting a resource to select another owner. */
  public static ResourceDeclarationDocument read(String moduleId, String owner, byte[] content)
      throws IOException {
    if (owner == null || owner.length() > 128 || !owner.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
      throw new ModuleCompositionException("INVALID_RESOURCE_OWNER", moduleId);
    }
    if (content == null || content.length > 2 * 1024 * 1024) {
      throw new ModuleCompositionException("MODULE_ASSET_LIMIT", moduleId);
    }
    var root = MAPPER.readTree(content);
    if (root == null
        || !root.isObject()
        || !root.path("resources").isArray()
        || (root.has("module")
            && (!root.path("module").isTextual()
                || !owner.equals(root.path("module").textValue())))) {
      throw new ModuleCompositionException("INVALID_RESOURCE_DECLARATION", moduleId);
    }
    var queue = new ArrayDeque<JsonNode>();
    root.get("resources").forEach(queue::add);
    var codes = new HashSet<String>();
    while (!queue.isEmpty()) {
      var resource = queue.removeFirst();
      var code = resource.path("code");
      var declaredOwner = resource.get("pluginId");
      if (!resource.isObject()
          || !code.isTextual()
          || code.textValue().isBlank()
          || !codes.add(code.textValue())
          || codes.size() > 10_000
          || (declaredOwner != null
              && !declaredOwner.isNull()
              && (!declaredOwner.isTextual() || !owner.equals(declaredOwner.textValue())))) {
        throw new ModuleCompositionException("INVALID_RESOURCE_OWNER_OR_CODE", moduleId);
      }
      var children = resource.get("children");
      if (children != null && !children.isNull()) {
        if (!children.isArray()) {
          throw new ModuleCompositionException("INVALID_RESOURCE_DECLARATION", moduleId);
        }
        children.forEach(queue::add);
      }
    }
    return new ResourceDeclarationDocument(root.get("resources"), codes);
  }
}
