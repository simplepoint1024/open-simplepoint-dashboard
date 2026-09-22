package org.simplepoint.plugin.storage.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.simplepoint.core.query.QueryCapabilities;
import org.simplepoint.data.jpa.ProjectionQuery;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageProviderConfig;
import org.simplepoint.plugin.storage.api.entity.ObjectStorageTenantQuota;
import org.simplepoint.plugin.storage.entity.StorageQuotaReadModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Read-only SQL adapters for globally managed storage configuration and quota lists. */
final class StorageListQueries {
  private final EntityManager em;

  StorageListQueries(EntityManager em) {
    this.em = em;
  }

  Page<ObjectStorageProviderConfig> providers(Map<String, String> filters, Pageable pageable) {
    var compiled = ProjectionQuery.prepare(ObjectStorageProviderConfig.class, filters, pageable);
    var cb = em.getCriteriaBuilder();
    var query = cb.createTupleQuery();
    var root = query.from(ObjectStorageProviderConfig.class);
    var fields = providerFields(cb, root);
    query
        .multiselect(root, fields.get("secretConfigured"))
        .where(cb.isNull(root.get("deletedAt")), compiled.predicate(cb, fields))
        .orderBy(compiled.orders(cb, fields, root.get("id")));
    var rows =
        em.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var countRoot = count.from(ObjectStorageProviderConfig.class);
    count
        .select(cb.count(countRoot))
        .where(
            cb.isNull(countRoot.get("deletedAt")),
            compiled.predicate(cb, providerFields(cb, countRoot)));
    long total = em.createQuery(count).getSingleResult();
    var content =
        rows.stream()
            .map(
                row -> {
                  var provider = row.get(0, ObjectStorageProviderConfig.class);
                  provider.setSecretConfigured(row.get(1, Boolean.class));
                  return provider;
                })
            .toList();
    return new PageImpl<>(content, pageable, total);
  }

  private Map<String, Expression<?>> providerFields(CriteriaBuilder cb, Root<?> root) {
    Map<String, Expression<?>> fields = new LinkedHashMap<>();
    for (var field : QueryCapabilities.declaration(ObjectStorageProviderConfig.class).value()) {
      if (!field.value().equals("secretConfigured")) {
        fields.put(field.value(), root.get(field.value()));
      }
    }
    fields.put(
        "secretConfigured",
        cb.<Boolean>selectCase()
            .when(
                cb.and(
                    cb.isNotNull(root.get("secretKeyCiphertext")),
                    cb.notEqual(cb.trim(root.get("secretKeyCiphertext")), "")),
                true)
            .otherwise(false));
    return fields;
  }

  Page<ObjectStorageTenantQuota> quotas(Map<String, String> filters, Pageable pageable) {
    var compiled = ProjectionQuery.prepare(ObjectStorageTenantQuota.class, filters, pageable);
    var cb = em.getCriteriaBuilder();
    var query = cb.createTupleQuery();
    var root = query.from(StorageQuotaReadModel.class);
    var fields = quotaFields(root);
    query
        .multiselect(
            fields.entrySet().stream()
                .map(entry -> entry.getValue().alias(entry.getKey()))
                .toArray(jakarta.persistence.criteria.Selection<?>[]::new))
        .where(compiled.predicate(cb, fields))
        .orderBy(compiled.orders(cb, fields, root.get("id")));
    List<Tuple> rows =
        em.createQuery(query)
            .setFirstResult((int) pageable.getOffset())
            .setMaxResults(pageable.getPageSize())
            .getResultList();
    var count = cb.createQuery(Long.class);
    var countRoot = count.from(StorageQuotaReadModel.class);
    count.select(cb.count(countRoot)).where(compiled.predicate(cb, quotaFields(countRoot)));
    long total = em.createQuery(count).getSingleResult();
    return new PageImpl<>(rows.stream().map(this::quota).toList(), pageable, total);
  }

  private Map<String, Expression<?>> quotaFields(Root<?> root) {
    Map<String, Expression<?>> fields = new LinkedHashMap<>();
    fields.put("id", root.get("id"));
    for (var field : QueryCapabilities.declaration(ObjectStorageTenantQuota.class).value()) {
      fields.put(field.value(), root.get(field.value()));
    }
    return fields;
  }

  private ObjectStorageTenantQuota quota(Tuple row) {
    var result = new ObjectStorageTenantQuota();
    result.setId(row.get("id", String.class));
    result.setTenantId(row.get("tenantId", String.class));
    result.setTenantName(row.get("tenantName", String.class));
    result.setQuotaBytes(row.get("quotaBytes", Long.class));
    result.setUsedBytes(row.get("usedBytes", Long.class));
    result.setRemainingBytes(row.get("remainingBytes", Long.class));
    result.setEnabled(row.get("enabled", Boolean.class));
    result.setDescription(row.get("description", String.class));
    result.setCreatedAt(row.get("createdAt", Instant.class));
    result.setUpdatedAt(row.get("updatedAt", Instant.class));
    return result;
  }
}
