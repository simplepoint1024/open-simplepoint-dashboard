package org.simplepoint.plugin.rbac.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import java.util.function.Supplier;
import org.hibernate.engine.spi.SessionFactoryImplementor;

/**
 * Holds the platform source snapshot through tenant import commit. Acquire before a tenant write
 * lock, matching platform catalog writers, so a newly imported mirror cannot miss propagation.
 */
final class TenantResourceSourceBoundary {
  private TenantResourceSourceBoundary() {}

  static void enter(EntityManager em, Supplier<? extends RuntimeException> unavailable) {
    if (!em.isJoinedToTransaction()) {
      throw unavailable.get();
    }
    // The dialect supplies PostgreSQL FOR SHARE (concurrent imports) and H2 FOR UPDATE.
    // This locks an existing installation row; it creates no resource version or new schema.
    String lock =
        em.getEntityManagerFactory()
            .unwrap(SessionFactoryImplementor.class)
            .getJdbcServices()
            .getDialect()
            .getReadLockString(-1);
    try {
      var rows =
          em.createNativeQuery(
                  "select revision from {h-schema}simpoint_resource_catalog_revision"
                      + " where id='catalog'"
                      + lock)
              .setFlushMode(FlushModeType.COMMIT)
              .setHint("jakarta.persistence.query.timeout", 10000)
              .getResultList();
      if (rows.size() != 1) {
        throw unavailable.get();
      }
    } catch (PersistenceException error) {
      var failure = unavailable.get();
      failure.initCause(error);
      throw failure;
    }
  }
}
