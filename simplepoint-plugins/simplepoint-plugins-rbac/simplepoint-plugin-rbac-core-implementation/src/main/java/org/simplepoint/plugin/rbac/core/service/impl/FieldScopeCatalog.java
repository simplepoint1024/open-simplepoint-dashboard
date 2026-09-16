package org.simplepoint.plugin.rbac.core.service.impl;

import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Catalog of locally managed entity fields. Remote modules must publish their own catalog. */
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
    return result;
  }

  public String validateResource(String resource, String field) {
    Map<String, List<String>> catalog = entries();
    String canonical = resource;
    if (!catalog.containsKey(resource)) {
      var matches = catalog.keySet().stream().filter(name -> name.substring(name.lastIndexOf('.') + 1).equals(resource)).toList();
      if (matches.size() != 1) throw new IllegalArgumentException("未知或有歧义的资源: " + resource);
      canonical = matches.getFirst();
    }
    if (!catalog.get(canonical).contains(field)) throw new IllegalArgumentException("资源字段不存在: " + resource + "#" + field);
    return canonical;
  }
}
