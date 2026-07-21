package org.simplepoint.security.oauth2.resourceserver.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.simplepoint.security.ResourceScopePolicy;
import org.simplepoint.security.entity.ResourceScopeType;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Read-only authority-to-scope catalog built from module resource declarations. */
public class ClasspathResourceScopeRegistry {

  private static final String RESOURCE_PATTERN = "classpath*:META-INF/simplepoint/resources/*.json";

  private final Map<String, Set<ResourceScopeType>> scopesByCode;

  /** Creates and validates a catalog from every resource manifest on the service classpath. */
  public ClasspathResourceScopeRegistry(final ObjectMapper objectMapper) throws IOException {
    Map<String, Set<ResourceScopeType>> scopes = new LinkedHashMap<>();
    var resolver = new PathMatchingResourcePatternResolver();
    for (Resource resource : resolver.getResources(RESOURCE_PATTERN)) {
      try (var input = resource.getInputStream()) {
        JsonNode document = objectMapper.readTree(input);
        JsonNode roots = document.isArray() ? document : document.path("resources");
        if (!roots.isArray()) {
          continue;
        }
        for (JsonNode root : roots) {
          collect(root, Set.of(), scopes, resource.getDescription());
        }
      }
    }
    this.scopesByCode = Collections.unmodifiableMap(scopes);
  }

  ClasspathResourceScopeRegistry(final Map<String, Set<ResourceScopeType>> scopesByCode) {
    Map<String, Set<ResourceScopeType>> scopes = new LinkedHashMap<>();
    scopesByCode.forEach((code, values) ->
        scopes.put(code, ResourceScopePolicy.effectiveScopes(values))
    );
    this.scopesByCode = Collections.unmodifiableMap(scopes);
  }

  /** Returns the declared effective scopes for an authority code. */
  public Optional<Set<ResourceScopeType>> findScopes(final String resourceCode) {
    if (resourceCode == null || resourceCode.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(scopesByCode.get(resourceCode));
  }

  /** Returns an immutable snapshot, primarily for diagnostics and tests. */
  public Map<String, Set<ResourceScopeType>> snapshot() {
    return scopesByCode;
  }

  private void collect(
      final JsonNode node,
      final Set<ResourceScopeType> parentScopes,
      final Map<String, Set<ResourceScopeType>> target,
      final String source
  ) {
    Set<ResourceScopeType> declared = readScopes(node);
    Set<ResourceScopeType> effective = declared.isEmpty()
        ? (parentScopes.isEmpty() ? ResourceScopePolicy.effectiveScopes(Set.of()) : parentScopes)
        : ResourceScopePolicy.effectiveScopes(declared);
    if (!parentScopes.isEmpty() && !ResourceScopePolicy.isValidChild(parentScopes, effective)) {
      throw new IllegalStateException("Resource scope exceeds parent boundary in " + source
          + ": " + node.path("code").asText("<unknown>"));
    }

    String code = node.path("code").asText(null);
    if (code != null && !code.isBlank()) {
      Set<ResourceScopeType> previous = target.putIfAbsent(code, effective);
      if (previous != null && !previous.equals(effective)) {
        throw new IllegalStateException("Conflicting resource scopes for " + code + " in " + source);
      }
    }

    JsonNode children = node.path("children");
    if (children.isArray()) {
      for (JsonNode child : children) {
        collect(child, effective, target, source);
      }
    }
  }

  private Set<ResourceScopeType> readScopes(final JsonNode node) {
    EnumSet<ResourceScopeType> scopes = EnumSet.noneOf(ResourceScopeType.class);
    JsonNode values = node.path("scopeTypes");
    if (values.isArray()) {
      values.forEach(value -> scopes.add(ResourceScopeType.valueOf(value.asText())));
    } else if (node.hasNonNull("scopeType")) {
      scopes.add(ResourceScopeType.valueOf(node.path("scopeType").asText()));
    }
    return scopes;
  }
}
