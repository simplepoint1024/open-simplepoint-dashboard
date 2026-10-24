package org.simplepoint.platform.http.servlet;

import java.util.Set;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleHttpPlan;

/** Ensures another MVC configurer cannot silently remove or steal a declared module prefix. */
final class ModuleHttpMappings {
  private ModuleHttpMappings() {}

  static void validate(ModuleHttpPlan plan, Class<?> controller, Set<String> paths) {
    var owner = plan.ownerOf(controller);
    var namespace = plan.namespaceOf(controller);
    if (owner.isPresent() && namespace.isEmpty()) {
      throw new ModuleCompositionException("UNDECLARED_MODULE_HTTP", controller.getName());
    }
    for (String path : paths) {
      if (namespace.isPresent()
          && plan.routes().get(owner.orElseThrow()).stream()
              .noneMatch(root -> ModuleHttpPlan.contains(root, path))) {
        throw new ModuleCompositionException("MODULE_HTTP_PREFIX_MISMATCH", controller.getName());
      }
      for (var entry : plan.routes().entrySet()) {
        if (entry.getValue().stream().anyMatch(root -> HttpRoutePaths.captures(root, path))
            && !owner.filter(entry.getKey()::equals).isPresent()) {
          throw new ModuleCompositionException(
              "MODULE_HTTP_NAMESPACE_CAPTURE", controller.getName());
        }
      }
    }
  }
}
