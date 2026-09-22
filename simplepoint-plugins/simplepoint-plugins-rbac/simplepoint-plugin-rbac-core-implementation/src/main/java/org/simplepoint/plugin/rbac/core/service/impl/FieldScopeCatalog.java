package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Catalog of local entities and trusted metadata published by remote modules. */
@Component
public class FieldScopeCatalog {
  private final EntityManager entityManager;

  public FieldScopeCatalog(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public Map<String, List<String>> entries() {
    Map<String, List<String>> result = new LinkedHashMap<>();
    entityManager.getMetamodel().getEntities().stream()
        .filter(entity -> entity.getJavaType().getPackageName().startsWith("org.simplepoint."))
        .sorted(java.util.Comparator.comparing(entity -> entity.getJavaType().getName()))
        .forEach(entity -> result.put(entity.getJavaType().getName(), entity.getAttributes().stream()
            .map(jakarta.persistence.metamodel.Attribute::getName).sorted().toList()));
    if (entityManager.getMetamodel().getEntities().stream()
        .anyMatch(entity -> entity.getJavaType() == org.simplepoint.security.entity.Resource.class)) {
      entityManager.createQuery("select r.permissionMetadata from Resource r where r.deletedAt is null"
          + " and r.permissionMetadata is not null", String.class).getResultList().forEach(json -> {
        var page = org.simplepoint.core.permission.PermissionPageDescriptor.fromJson(json);
        page.models().stream().filter(org.simplepoint.core.permission.PermissionModelDescriptor::fields)
            .forEach(model -> result.merge(model.model(), model.properties().keySet().stream().sorted().toList(),
                (local, remote) -> java.util.stream.Stream.concat(local.stream(), remote.stream()).distinct().sorted().toList()));
      });
    }
    return result;
  }

  /** Page codes are selected by users; canonical model names remain an execution detail. */
  public Map<String, org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.PageFields> pages() {
    var context = org.simplepoint.core.AuthorizationContextHolder.getContext();
    if (context == null || context.getUserId() == null) {
      throw new org.springframework.security.access.AccessDeniedException("授权上下文不可用");
    }
    Map<String, org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.PageFields> result = new java.util.TreeMap<>();
    if (context.getResources() == null || context.getResources().isEmpty()) return result;
    entityManager.createQuery("select r.code,r.alias,r.name,r.label,r.title,r.permissionMetadata from Resource r"
        + " where r.deletedAt is null and r.disabled=false and r.type='PAGE' and r.permissionMetadata is not null", Object[].class)
        .getResultList().forEach(row -> {
          String code = (String) row[0];
          if (!context.getResources().contains(code)) return;
          var descriptor = org.simplepoint.core.permission.PermissionPageDescriptor.fromJson((String) row[5]);
          var models = descriptor.models().stream().filter(model -> model.fields() && !model.properties().isEmpty()).toList();
          if (models.isEmpty()) return;
          String title = java.util.stream.Stream.of(row[1], row[2], row[3], row[4], code)
              .filter(value -> value instanceof String text && !text.isBlank()).map(Object::toString).findFirst().orElse(code);
          result.put(code, new org.simplepoint.plugin.rbac.core.api.service.FieldScopeService.PageFields(title, models));
        });
    return result;
  }

  public String validateResource(String resource, String field) {
    return validateResource(entries(), resource, field);
  }

  public String validateResource(Map<String, List<String>> catalog, String resource, String field) {
    String canonical = resource;
    if (!catalog.containsKey(resource)) {
      var matches = catalog.keySet().stream().filter(name -> name.substring(name.lastIndexOf('.') + 1).equals(resource)).toList();
      if (matches.size() != 1) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("未知或有歧义的资源: " + resource);
      canonical = matches.getFirst();
    }
    if (!catalog.get(canonical).contains(field)) throw new org.simplepoint.core.permission.InvalidPermissionPolicyException("资源字段不存在: " + resource + "#" + field);
    return canonical;
  }
}
