package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMappingView;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/**
 * Detached, authorized mapping observation; saving always rechecks the returned revision/version.
 */
final class RecordReferenceMappingRead {
  private final RuntimeRecordModelResolver models;
  private final RuntimeRecordAccessPolicy policies;
  private final RuntimeCalciteRecordBackend backend;
  private final RecordCalculationWriteSupport calculations;
  private final RecordReferenceMappingTargets targets;

  RecordReferenceMappingRead(
      RuntimeRecordModelResolver models,
      RuntimeRecordAccessPolicy policies,
      RuntimeCalciteRecordBackend backend,
      RecordCalculationWriteSupport calculations,
      RecordReferenceMappingTargets targets) {
    this.models = models;
    this.policies = policies;
    this.backend = backend;
    this.calculations = calculations;
    this.targets = targets;
  }

  RecordReferenceMappingView read(RuntimeActor actor, RecordReferenceMappingQuery query) {
    var deadline = new RecordExecutionDeadline(query.timeoutMs());
    var parent = models.resolve(actor, query.physicalModelId(), query.schemaReference());
    var access = policies.authorize(actor, parent);
    var installed =
        backend.calculation(
            parent, actor.tenantId(), query.writePolicyVersion(), deadline.remainingMillis());
    var calculation = calculations.bind(actor, parent, installed);
    if (calculation == null || parent.activeSchema().owner() != null) {
      throw RecordReferenceAdmission.incompatible();
    }
    var plan =
        backend.referencePlan(
            parent,
            List.copyOf(calculation.details().values()),
            new RecordWriteAdmission(actor.tenantId(), query.writePolicyVersion()),
            deadline.remainingMillis());
    if (plan == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    plan.verify(installed);
    var binding = plan.bindings().get(query.relationId());
    if (binding == null || binding.rule().mappings().isEmpty()) {
      throw RecordReferenceAdmission.incompatible();
    }
    var source = RecordReferenceMappingBinding.source(plan, query.relationId());
    var sourceAccess =
        source.model().physicalModelId().equals(parent.physicalModelId())
            ? access
            : policies.owned(actor, source.model(), access);
    var reference =
        source.model().requestedSchema().fields().stream()
            .filter(field -> field.fieldId().equals(binding.rule().source().fieldId()))
            .findFirst()
            .orElseThrow(RecordReferenceAdmission::incompatible);
    if (!sourceAccess.writableFields().contains(reference.code())
        || !sourceAccess.readableFields().contains(reference.code())
        || sourceAccess.maskedFields().contains(reference.code())) {
      throw conflict();
    }
    var defaults = Set.copyOf(query.applyDefaultMappingIds());
    var selected = RecordReferenceMappingFields.select(binding.rule(), defaults);
    var writable =
        sourceAccess.writableFields().stream()
            .filter(code -> source.model().requestedField(code) != null)
            .filter(sourceAccess.readableFields()::contains)
            .filter(code -> !sourceAccess.maskedFields().contains(code))
            .map(source.model()::activeCode)
            .collect(java.util.stream.Collectors.toSet());
    for (var mapping : binding.rule().mappings()) {
      if ((mapping.mode()
                  == org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceMapping.Mode
                      .SERVER_SNAPSHOT
              || defaults.contains(mapping.mappingId()))
          && !writable.contains(
              RecordReferenceMappingBinding.field(source.model(), mapping.destinationFieldId())
                  .code())) {
        throw conflict();
      }
    }
    RecordReferenceCopyPlan.Prepared prepared;
    if (query.targetRecordId() == null) {
      var clear = RecordReferenceMappingClear.bind(plan, query.relationId(), actor);
      prepared = clear.prepare(clear.observe(), writable, defaults);
    } else {
      if (binding.target() == null || !actor.equals(binding.target().access().actor())) {
        throw conflict();
      }
      var target =
          targets
              .read(binding.target(), Set.of(query.targetRecordId()), selected, deadline)
              .get(query.targetRecordId());
      var mapping = plan.mapping(query.relationId());
      prepared =
          mapping
              .copies()
              .prepare(
                  query.targetRecordId(),
                  target.value(),
                  mapping.observe(target.value()),
                  binding.target().access().readableFields(),
                  binding.target().access().maskedFields(),
                  writable,
                  defaults);
    }
    deadline.remainingMillis();
    return new RecordReferenceMappingView(
        prepared.observed(), prepared.managed(), prepared.suggestions());
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "Reference mapping observation is unavailable under the current field policy");
  }
}
