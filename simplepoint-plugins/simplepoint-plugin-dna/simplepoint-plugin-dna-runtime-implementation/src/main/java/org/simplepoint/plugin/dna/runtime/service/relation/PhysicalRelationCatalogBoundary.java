package org.simplepoint.plugin.dna.runtime.service.relation;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalRelationCatalogEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database lock shared by schema registration/publication and admitted target transactions. */
@Component
public class PhysicalRelationCatalogBoundary {
  private final EntityManager em;

  public PhysicalRelationCatalogBoundary(EntityManager em) {
    this.em = em;
  }

  /**
   * Model registration only; no runtime DDL and no recovery from a poisoned unique-violation
   * transaction.
   */
  public void initialize(String tenant) {
    requireTransaction(tenant);
    em.createNativeQuery(
            "INSERT INTO {h-schema}simpoint_dna_relation_catalogs (tenant_id, revision, created_at,"
                + " updated_at) VALUES (:tenant, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) ON"
                + " CONFLICT (tenant_id) DO NOTHING")
        .setParameter("tenant", tenant)
        .executeUpdate();
    write(tenant);
  }

  public long read(String tenant) {
    return lock(tenant, false).getRevision();
  }

  public void write(String tenant) {
    lock(tenant, true);
  }

  public void requireHeld(String tenant, boolean write) {
    requireTransaction(tenant);
    var locks = ownership(false);
    if (locks == null || !locks.held(tenant, write)) {
      throw new IllegalStateException("Relation catalog access requires its transaction lock");
    }
  }

  private PhysicalRelationCatalogEntity lock(String tenant, boolean write) {
    requireTransaction(tenant);
    var row = em.find(PhysicalRelationCatalogEntity.class, tenant);
    if (row == null)
      throw new IllegalStateException("Relation catalog is not installed for this tenant");
    var locks = ownership(true);
    if (locks.held(tenant, write)) return row;
    if (write && locks.held(tenant, false)) {
      throw new IllegalStateException(
          "A relation catalog read transaction cannot upgrade to a writer");
    }
    em.refresh(row, write ? LockModeType.PESSIMISTIC_WRITE : LockModeType.PESSIMISTIC_READ);
    locks.tenants.put(tenant, write);
    if (write) {
      row.setRevision(Math.addExact(row.getRevision(), 1));
      row.setUpdatedAt(Instant.now());
    }
    return row;
  }

  private CatalogLocks ownership(boolean create) {
    Object persistenceContext = em.getDelegate();
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof CatalogLocks locks
          && locks.persistenceContext == persistenceContext) return locks;
    }
    if (!create) return null;
    var locks = new CatalogLocks(persistenceContext);
    TransactionSynchronizationManager.registerSynchronization(locks);
    return locks;
  }

  private void requireTransaction(String tenant) {
    if (tenant == null
        || tenant.isBlank()
        || tenant.length() > 255
        || !em.isJoinedToTransaction()
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Relation catalog requires a tenant-scoped control transaction");
    }
  }

  /**
   * Hibernate can replace an entity's reported lock mode after an automatic flush. Database row
   * locks still last until transaction completion. Track only successfully acquired locks in that
   * transaction's synchronization set, which is suspended for REQUIRES_NEW and discarded on exit.
   */
  private static final class CatalogLocks implements TransactionSynchronization {
    private final Object persistenceContext;
    private final Map<String, Boolean> tenants = new HashMap<>();

    private CatalogLocks(Object persistenceContext) {
      this.persistenceContext = persistenceContext;
    }

    private boolean held(String tenant, boolean write) {
      return tenants.containsKey(tenant) && (!write || tenants.get(tenant));
    }
  }
}
