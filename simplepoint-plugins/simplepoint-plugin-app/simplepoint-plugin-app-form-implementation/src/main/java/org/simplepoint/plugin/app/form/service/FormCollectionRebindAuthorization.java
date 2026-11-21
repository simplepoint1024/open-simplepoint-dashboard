package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSnapshotTarget;
import org.simplepoint.plugin.dna.runtime.api.service.DnaFormRuntimeService;
import org.springframework.stereotype.Component;

/** Authorizes all mapped records before accepting an owner-scoped publication intent. */
@Component
public class FormCollectionRebindAuthorization {
  private final FormRelationTargetResolver relations;
  private final DnaInvocationContextFactory contexts;
  private final DnaFormRuntimeService dna;

  public FormCollectionRebindAuthorization(
      FormRelationTargetResolver relations,
      DnaInvocationContextFactory contexts,
      DnaFormRuntimeService dna) {
    this.relations = relations;
    this.contexts = contexts;
    this.dna = dna;
  }

  void requireAccess(String tenant, FormCollectionRebinding.Access access) {
    long deadline = System.nanoTime() + 30_000_000_000L;
    var records = access.migration().targetRecords();
    requireAll(tenant, access.source(), records.keySet(), deadline);
    requireAll(tenant, access.destination(), records.values(), deadline);
  }

  private void requireAll(
      String tenant, RecordSnapshotTarget entrance, Collection<String> ids, long deadline) {
    var target = relations.requireExactEntrance(tenant, entrance);
    var authority = contexts.forCollectionMigrationRead(target);
    var schema = new RecordSchemaReference(target.physicalModelVersion(), target.storageChecksum());
    var unique = new ArrayList<Object>(new LinkedHashSet<>(ids));
    for (int index = 0; index < unique.size(); index += 100) {
      requireTime(deadline);
      var batch = unique.subList(index, Math.min(index + 100, unique.size()));
      var filter = new RecordFilter(RecordFilterOperator.IN, "id", batch, List.of());
      try {
        if (dna.countRecords(
                authority, new RecordCountQuery(target.physicalModelId(), schema, filter))
            == batch.size()) {
          continue;
        }
      } catch (DnaRuntimeException denied) {
        if (denied.errorCode() != DnaRuntimeErrorCode.RECORD_ACCESS_DENIED) {
          throw denied;
        }
      }
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RESOURCE_ACCESS_DENIED,
          "A mapped record is unavailable in its published target page");
    }
    requireTime(deadline);
  }

  private static void requireTime(long deadline) {
    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RUNTIME_UNAVAILABLE,
          "Collection replacement access check exceeded its execution budget");
    }
  }
}
