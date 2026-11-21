package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.security.access.AccessDeniedException;

/** Authorizes frozen labels using current target identities, without reading replacement values. */
final class RecordReferenceSnapshotReader {
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy policies;
  private final RecordSqlCompiler compiler;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationAccess calculations;

  RecordReferenceSnapshotReader(
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies,
      RecordSqlCompiler compiler,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationAccess calculations) {
    this.models = models;
    this.policies = policies;
    this.compiler = compiler;
    this.backend = backend;
    this.calculations = calculations;
  }

  OwnedRecordView authorize(
      RuntimeActor actor, OwnedRecordView source, RecordExecutionDeadline deadline) {
    if (source == null || source.referenceSnapshots().isEmpty()) {
      return source;
    }
    Map<Target, List<RecordReferenceSnapshot>> grouped = new LinkedHashMap<>();
    for (var label : source.referenceSnapshots()) {
      var target =
          new Target(
              label.targetModelId(),
              label.targetAppId(),
              label.targetFormId(),
              label.targetResourceCode(),
              label.labelFieldId());
      grouped.computeIfAbsent(target, ignored -> new ArrayList<>()).add(label);
    }
    Set<RecordReferenceSnapshot> allowed = new HashSet<>();
    for (var entry : grouped.entrySet()) {
      deadline.remainingMillis();
      var target = entry.getKey();
      var authorization = AuthorizationContextHolder.getContext();
      if (authorization == null
          || authorization.dataScopeFor(target.resource()) == null
          || authorization.fieldPermissionsFor(target.resource()) == null) {
        continue;
      }
      try {
        var model = models.resolveCurrent(actor, target.model());
        if (!target.app().equals(model.appId()) || !target.form().equals(model.formId())) {
          throw RecordReferenceSnapshotProvenance.invalid();
        }
        var field =
            model.requestedSchema().fields().stream()
                .filter(value -> value.fieldId().equals(target.field()))
                .findFirst()
                .orElse(null);
        if (field == null
            || (field.type()
                    != org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.STRING
                && field.type()
                    != org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType.TEXT)) {
          continue;
        }
        var access = policies.authorizeReference(actor, model, target.resource());
        if (access.resourceGeneration() < 1 || !readable(access, field.code())) {
          continue;
        }
        // The caller has closed the source transaction. Each target uses its own resource and
        // installed-policy fence; all groups and batches share the original request deadline.
        Set<String> ids =
            backend.read(
                model,
                List.of(),
                access,
                deadline.remainingMillis(),
                session -> {
                  var current =
                      calculations
                          .resolveReference(model, access, session.calculation())
                          .apply(model, access);
                  if (!readable(current, field.code())) {
                    return Set.of();
                  }
                  var requested =
                      new ArrayList<>(
                          new LinkedHashSet<>(
                              entry.getValue().stream()
                                  .map(RecordReferenceSnapshot::targetRecordId)
                                  .toList()));
                  Set<String> found = new LinkedHashSet<>();
                  for (int offset = 0; offset < requested.size(); offset += 100) {
                    deadline.remainingMillis();
                    var batch = requested.subList(offset, Math.min(offset + 100, requested.size()));
                    // Retained history is governed by read permission, not current candidate
                    // eligibility.
                    found.addAll(
                        RecordReferenceVisibility.select(
                            model, current, batch, compiler, session.query()));
                  }
                  return found;
                });
        entry.getValue().stream()
            .filter(label -> ids.contains(label.targetRecordId()))
            .forEach(allowed::add);
      } catch (AccessDeniedException unavailable) {
        // A revoked target never prevents reading unrelated authorized historical source values.
      } catch (DnaRuntimeException failure) {
        if (!unavailable(failure.errorCode())) {
          throw failure;
        }
      }
    }
    deadline.remainingMillis();
    return new OwnedRecordView(
        source.parent(),
        source.details(),
        source.referenceSnapshots().stream().filter(allowed::contains).toList());
  }

  private static boolean readable(RecordAccessContext access, String label) {
    return access.readableFields().containsAll(List.of("id", label))
        && !access.maskedFields().contains("id")
        && !access.maskedFields().contains(label);
  }

  private static boolean unavailable(DnaRuntimeErrorCode code) {
    return Set.of(
            DnaRuntimeErrorCode.RECORD_ACCESS_DENIED,
            DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_FOUND,
            DnaRuntimeErrorCode.PHYSICAL_MODEL_NOT_ACTIVE,
            DnaRuntimeErrorCode.RESOURCE_PROJECTION_SUPERSEDED)
        .contains(code);
  }

  private record Target(String model, String app, String form, String resource, String field) {}
}
