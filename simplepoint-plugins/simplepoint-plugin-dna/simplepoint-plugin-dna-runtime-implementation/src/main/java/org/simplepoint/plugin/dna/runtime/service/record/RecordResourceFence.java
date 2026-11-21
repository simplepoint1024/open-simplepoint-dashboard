package org.simplepoint.plugin.dna.runtime.service.record;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalog;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds a shared control-plane fence through target reads/writes; offline takes its write lock. */
@Component
public class RecordResourceFence {
  private final PhysicalRelationCatalog relations;
  private final EntityManager em;
  private final TransactionTemplate transactions;

  /** Uses the control database, independent of the form's physical data source. */
  public RecordResourceFence(
      EntityManager em, PlatformTransactionManager manager, PhysicalRelationCatalog relations) {
    this.relations = relations;
    this.em = em;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setTimeout(45);
  }

  long generation(String tenant, String model, String resource) {
    var projections =
        em.createQuery(
                "from PhysicalResourceProjectionEntity p where p.tenantId = :tenant "
                    + "and p.resourceCode = :resource",
                PhysicalResourceProjectionEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("resource", resource)
            .setMaxResults(2)
            .getResultList();
    if (projections.isEmpty()) {
      return 0;
    }
    if (projections.size() != 1) throw unavailable();
    var projection = projections.getFirst();
    if (projection.isFormallyOffline()
        || projection.getOwnerKey() != null && !model.equals(projection.getPhysicalModelId())) {
      throw new AccessDeniedException("Record resource is offline or superseded");
    }
    return projection.getGeneration();
  }

  <T> T execute(
      String tenant,
      String model,
      long generation,
      java.util.Collection<PhysicalRelationCatalog.Scope> scopes,
      Supplier<T> operation) {
    String resource = ResourcePolicyContext.get();
    if (resource == null || resource.isBlank()) {
      throw new AccessDeniedException("Record resource context is required");
    }
    return execute(
        tenant,
        List.of(new RecordResourceGuard(model, resource, generation, false)),
        scopes,
        operation);
  }

  /**
   * Locks every selected resource in a deterministic order before opening the target transaction.
   */
  <T> T execute(
      String tenant,
      Collection<RecordResourceGuard> resources,
      Collection<PhysicalRelationCatalog.Scope> scopes,
      Supplier<T> operation) {
    if (resources == null || resources.isEmpty() || resources.size() > 128) {
      throw new IllegalArgumentException("Record resource admission budget exceeded");
    }
    var ordered =
        resources.stream()
            .distinct()
            .sorted(
                Comparator.comparing(RecordResourceGuard::resourceCode)
                    .thenComparing(RecordResourceGuard::modelId)
                    .thenComparingLong(RecordResourceGuard::generation))
            .toList();
    return transactions.execute(
        status -> {
          relations.admit(tenant, scopes);
          for (var resource : ordered) requireActive(tenant, resource);
          return operation.get();
        });
  }

  private void requireActive(String tenant, RecordResourceGuard guard) {
    var projections =
        em.createQuery(
                "from PhysicalResourceProjectionEntity p where p.tenantId = :tenant "
                    + "and p.resourceCode = :resource",
                PhysicalResourceProjectionEntity.class)
            .setParameter("tenant", tenant)
            .setParameter("resource", guard.resourceCode())
            .setLockMode(LockModeType.PESSIMISTIC_READ)
            .setMaxResults(2)
            .getResultList();
    if (projections.isEmpty()) {
      if (guard.managed() || guard.generation() != 0) throw unavailable();
      return; // Ordinary legacy bindings have no managed generation; authorization checked their
              // binding.
    }
    if (projections.size() != 1) throw unavailable();
    var projection = projections.getFirst();
    if (projection.isFormallyOffline()
        || projection.getGeneration() != guard.generation()
        || projection.getOwnerKey() != null
            && !guard.modelId().equals(projection.getPhysicalModelId())
        || guard.managed()
            && (projection.getOwnerKey() == null || projection.getGeneration() < 1)) {
      throw unavailable();
    }
    if (projection.getOwnerKey() == null) {
      Long bindings =
          em.createQuery(
                  "select count(b) from PhysicalModelResourceBindingEntity b where b.tenantId ="
                      + " :tenant and b.resourceCode = :resource and b.physicalModelId = :model and"
                      + " b.deletedAt is null",
                  Long.class)
              .setParameter("tenant", tenant)
              .setParameter("resource", guard.resourceCode())
              .setParameter("model", guard.modelId())
              .getSingleResult();
      if (bindings != 1) throw unavailable();
    }
  }

  private static AccessDeniedException unavailable() {
    return new AccessDeniedException("Record resource is offline, unbound or superseded");
  }
}
