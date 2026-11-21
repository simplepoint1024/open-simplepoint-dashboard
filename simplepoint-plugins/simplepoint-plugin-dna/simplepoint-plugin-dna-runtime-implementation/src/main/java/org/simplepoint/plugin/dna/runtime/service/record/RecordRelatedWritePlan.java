package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import java.util.function.Function;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilter;
import org.simplepoint.plugin.dna.runtime.api.record.RecordFilterOperator;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;

/** Relation membership is checked in the same version-fenced aggregate transaction as the write. */
record RecordRelatedWritePlan(RecordRelatedSelection selection, RelatedRecordScope scope) {
  static RecordRelatedWritePlan compile(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      RecordCalculationWriteSupport.Binding binding,
      RelatedRecordScope scope,
      RuntimeRecordAccessPolicy permissions,
      RecordCalculationAccess visibility) {
    if (binding == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    var rule =
        binding.installed().policy().definition().referencesOrEmpty().stream()
            .filter(value -> !value.retired() && value.relationId().equals(scope.relationId()))
            .findFirst()
            .orElseThrow(RecordReferenceAdmission::incompatible);
    if (!rule.targetModelId().equals(scope.targetModelId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    boolean owned = !rule.source().scope().equals("parent");
    var source = owned ? binding.details().get(rule.source().scope()) : parent;
    if (source == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    if (owned) {
      OwnedRecordBinding.parentCode(parent, source);
    }
    var raw = owned ? permissions.owned(access.actor(), source, access) : access;
    var rules = visibility.resolve(parent, access, binding.installed());
    var selected =
        new RecordRelatedSelection(
            source, rules.apply(source, raw), rule, scope.targetRecordId(), scope.sourceRowId());
    String code = selected.sourceCode();
    if (scope.mode() == RelatedRecordScope.Mode.UNLINK
        && (!source.requestedField(code).nullable()
            || !source.activeField(code).nullable()
            || owned != (scope.sourceRowId() != null))) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.RECORD_COMMAND_INVALID,
          "Unlink requires a nullable reference and an exact row for owned details");
    }
    return new RecordRelatedWritePlan(selected, scope);
  }

  RecordWritePreparation verify(
      RuntimeRecordModel parent,
      RecordAccessContext access,
      OwnedRecordCommand command,
      String parentId,
      RecordWritePreparation write,
      java.util.Map<String, RecordCalculationWriteSupport.Scope> models,
      RecordSqlCompiler compiler,
      Function<RecordSelectPlan, CalciteQueryResult> reader) {
    var mutation = command.parent().mutations().getFirst();
    if (!(mutation instanceof RecordCreateMutation)) {
      var request =
          new RecordPageQuery(
              parent.physicalModelId(),
              command.parent().schemaReference(),
              0,
              1,
              List.of("id"),
              new RecordFilter(RecordFilterOperator.EQUALS, "id", List.of(parentId), List.of()),
              List.of());
      var result = reader.apply(compiler.page(parent, access, request, selection));
      if (result.truncated() || result.rows().size() != 1) {
        throw conflict();
      }
    }
    if (!(mutation instanceof RecordDeleteMutation)) {
      boolean retained =
          write.references().stream()
              .anyMatch(
                  check ->
                      check.relationId().equals(scope.relationId())
                          && check.sourceModelId().equals(selection.source().physicalModelId())
                          && (scope.mode() == RelatedRecordScope.Mode.UNLINK
                              ? check.targetRecordId() == null
                                  && check
                                      .sourceRecordId()
                                      .equals(
                                          scope.sourceRowId() == null
                                              ? parentId
                                              : scope.sourceRowId())
                              : scope.targetRecordId().equals(check.targetRecordId())));
      if (!retained) {
        throw conflict();
      }
    }
    if (scope.mode() == RelatedRecordScope.Mode.UNLINK) {
      RecordRelatedUnlink.verify(
          new RecordCalculationWriteSupport.Scope(parent, access),
          models,
          selection,
          scope,
          command,
          parentId,
          write,
          compiler,
          reader);
    }
    return new RecordWritePreparation(
        write.statements(),
        write.snapshot(),
        write.receiptCount(),
        write.references(),
        new RecordReferenceRead(
            scope.relationId(), selection.source().physicalModelId(), scope.targetRecordId()),
        write.copies());
  }

  private static DnaRuntimeException conflict() {
    return new DnaRuntimeException(
        DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT,
        "The record no longer belongs to this relation or the requested transition changes its"
            + " scope");
  }
}
