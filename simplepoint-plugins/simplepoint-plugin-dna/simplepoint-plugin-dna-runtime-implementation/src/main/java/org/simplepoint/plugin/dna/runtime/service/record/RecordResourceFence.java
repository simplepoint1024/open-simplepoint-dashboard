package org.simplepoint.plugin.dna.runtime.service.record;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.function.Supplier;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalResourceProjectionEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds a shared control-plane fence until the target write commits; offline takes its write lock. */
@Component
public class RecordResourceFence {
  private final EntityManager em;
  private final TransactionTemplate transactions;

  /** Uses the control database, independent of the form's physical data source. */
  public RecordResourceFence(EntityManager em, PlatformTransactionManager manager) {
    this.em = em;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setTimeout(45);
  }

  long generation(String tenant, String model, String resource) {
    var projections = em.createQuery("from PhysicalResourceProjectionEntity p where p.tenantId = :tenant "
        + "and p.resourceCode = :resource", PhysicalResourceProjectionEntity.class)
        .setParameter("tenant", tenant).setParameter("resource", resource).getResultList();
    if (projections.isEmpty()) {
      return 0;
    }
    var projection = projections.getFirst();
    if (projection.isFormallyOffline()
        || projection.getOwnerKey() != null && !model.equals(projection.getPhysicalModelId())) {
      throw new AccessDeniedException("Record resource is offline or superseded");
    }
    return projection.getGeneration();
  }

  <T> T execute(String tenant, String model, long generation, Supplier<T> operation) {
    String resource = ResourcePolicyContext.get();
    if (resource == null || resource.isBlank()) {
      throw new AccessDeniedException("Record resource context is required");
    }
    return transactions.execute(status -> {
      var projections = em.createQuery("from PhysicalResourceProjectionEntity p where p.tenantId = :tenant "
          + "and p.resourceCode = :resource", PhysicalResourceProjectionEntity.class)
          .setParameter("tenant", tenant).setParameter("resource", resource)
          .setLockMode(LockModeType.PESSIMISTIC_READ).getResultList();
      if (!projections.isEmpty()) {
        var projection = projections.getFirst();
        if (projection.isFormallyOffline()
            || projection.getOwnerKey() != null && !model.equals(projection.getPhysicalModelId())
            || projection.getGeneration() != generation) {
          throw new AccessDeniedException("Record resource is offline or superseded");
        }
        if (projection.getOwnerKey() == null) {
          Long bindings = em.createQuery("select count(b) from PhysicalModelResourceBindingEntity b "
              + "where b.tenantId = :tenant and b.resourceCode = :resource and b.physicalModelId = :model "
              + "and b.deletedAt is null", Long.class).setParameter("tenant", tenant)
              .setParameter("resource", resource).setParameter("model", model).getSingleResult();
          if (bindings == 0) {
            throw new AccessDeniedException("Record resource binding has been revoked");
          }
        }
      }
      return operation.get();
    });
  }
}
