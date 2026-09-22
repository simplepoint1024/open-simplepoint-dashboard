package org.simplepoint.security.oauth2.resourceserver.scope;

import java.util.LinkedHashMap;
import java.util.Map;
import org.simplepoint.core.base.controller.BaseController;
import org.simplepoint.core.permission.PermissionModelDescriptor;
import org.simplepoint.core.permission.PermissionPageDescriptor;
import org.simplepoint.core.permission.ResourcePermissionCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Discovers standard pages from controller generics and their server-declared read authority. */
public final class StandardResourcePermissionCatalog implements ResourcePermissionCatalog {
  private final ObjectProvider<RequestMappingHandlerMapping> mappings;
  private final ClasspathResourceScopeRegistry scopes;
  private volatile Map<String, PermissionPageDescriptor> entries;

  public StandardResourcePermissionCatalog(ObjectProvider<RequestMappingHandlerMapping> mappings,
      ClasspathResourceScopeRegistry scopes) {
    this.mappings = mappings;
    this.scopes = scopes;
  }

  @Override
  public synchronized Map<String, PermissionPageDescriptor> entries() {
    if (entries != null) return entries;
    Map<String, Map<String, PermissionModelDescriptor>> result = new LinkedHashMap<>();
    mappings.orderedStream().forEach(mapping -> mapping.getHandlerMethods().values().forEach(handler -> {
      var explicit = handler.getBeanType().getAnnotation(org.simplepoint.core.permission.PermissionPage.class);
      if (explicit != null) {
        var descriptor = PermissionModelDescriptor.describe(explicit.model());
        result.computeIfAbsent(explicit.value(), ignored -> new LinkedHashMap<>()).put(descriptor.model(), descriptor);
        return;
      }
      if (!BaseController.class.isAssignableFrom(handler.getBeanType())) return;
      scopes.findControllerPage(handler.getBeanType()).ifPresent(page -> {
        Object bean = handler.createWithResolvedBean().getBean();
        var descriptor = ((BaseController<?, ?, ?>) bean).permissionModel();
        if (descriptor == null) return;
        var previous = result.computeIfAbsent(page, ignored -> new LinkedHashMap<>()).putIfAbsent(descriptor.model(), descriptor);
        if (previous != null && !previous.equals(descriptor)) {
          throw new IllegalStateException("Conflicting standard permission models for " + page);
        }
      });
    }));
    Map<String, PermissionPageDescriptor> pages = new LinkedHashMap<>();
    result.forEach((page, models) -> pages.put(page, new PermissionPageDescriptor(models.values().stream()
        .sorted(java.util.Comparator.comparing(PermissionModelDescriptor::model)).toList())));
    entries = Map.copyOf(pages);
    return entries;
  }
}
