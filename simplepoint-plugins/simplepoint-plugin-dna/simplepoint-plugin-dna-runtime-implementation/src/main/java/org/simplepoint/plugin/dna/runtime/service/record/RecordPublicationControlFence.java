package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.model.PhysicalModelStatus;
import org.simplepoint.plugin.dna.runtime.entity.PhysicalModelEntity;
import org.simplepoint.plugin.dna.runtime.service.relation.PhysicalRelationCatalog;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds control model locks across one bounded publication target transaction, including commit. */
@Component
public class RecordPublicationControlFence {
  private final PhysicalRelationCatalog relations;
  private final RecordMaintenanceControl maintenance;
  private final TransactionTemplate transactions;

  /** Control transactions only fence metadata; the target journal owns publication recovery. */
  public RecordPublicationControlFence(final RecordMaintenanceControl maintenance, final PlatformTransactionManager manager, final PhysicalRelationCatalog relations) {
    this.relations = relations;
    this.maintenance = maintenance;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.transactions.setTimeout(45);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  <T> T execute(final String tenant, final Collection<String> models,
      final Function<Map<String, PhysicalModelEntity>, T> targetTransaction) {
    return execute(tenant, models, new RecordExecutionDeadline(45_000), targetTransaction);
  }

  <T> T execute(final String tenant, final Collection<String> models, final RecordExecutionDeadline deadline,
      final Function<Map<String, PhysicalModelEntity>, T> targetTransaction) {
    var attempt = new TransactionTemplate(java.util.Objects.requireNonNull(transactions.getTransactionManager()), transactions);
    attempt.setTimeout(Math.max(1, (deadline.remainingMillis() + 999) / 1000));
    return attempt.execute(status -> {
      relations.admitModels(tenant, models);
      var locked = maintenance.lockModels(tenant, models);
      for (var model : locked.values()) {
        if (model.getStatus() != PhysicalModelStatus.ACTIVE || model.getActiveVersion() == null) {
          throw new DnaRuntimeException(DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE,
              "Calculation publication requires active models outside schema migration");
        }
      }
      // Resource mutations also need these model locks. Read generations without taking resource
      // locks here; acquiring them after model locks would invert the existing resource lock order.
      deadline.remainingMillis();
      return targetTransaction.apply(locked);
    });
  }
}
