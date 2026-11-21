package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.simplepoint.core.datascopeannotation.DataScopeCondition;
import org.simplepoint.data.calcite.core.query.CalciteQueryResult;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionEdgeMutation;
import org.simplepoint.plugin.dna.runtime.api.record.CollectionRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordSchemaReference;
import org.simplepoint.plugin.dna.runtime.api.schema.LogicalFieldType;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.simplepoint.plugin.dna.runtime.service.support.StandardLogicalValueConverter;

class RecordCollectionObservationReaderTest {
  private final RecordCollectionBinding binding =
      RecordCollectionPublicationTest.binding(LogicalFieldType.STRING);
  private final RecordSqlCompiler compiler =
      new RecordSqlCompiler(new StandardLogicalValueConverter());

  @Test
  void batchesQualifiedReadsAndRestrictsEligibilityToLinkingTargets() {
    for (var mode : CollectionEdgeMutation.Mode.values()) {
      var plans = new ArrayList<RecordSelectPlan>();
      var reader = reader(mode, access(Set.of()), access(Set.of()));
      final var observed =
          reader.read(
              plan -> {
                plans.add(plan);
                if (plan.sql().contains("\"edges\"")) {
                  return rows(List.of());
                }
                return rows(List.of(List.of(plans.size() == 1 ? id(1) : id(2), 0L)));
              });
      assertThat(plans).hasSize(mode == CollectionEdgeMutation.Mode.LINK ? 4 : 3);
      assertThat(plans.getFirst().parameters()).contains("DRAFT", "tenant");
      assertThat(plans.get(1).parameters()).doesNotContain("ACTIVE");
      if (mode == CollectionEdgeMutation.Mode.LINK) {
        assertThat(plans.get(2).parameters()).contains("ACTIVE", "tenant");
      }
      assertThat(observed.targetVersions()).containsEntry(id(2), 0L);
      assertThat(observed.edges()).containsEntry(id(2), RecordCollectionEdgeState.absent());
    }
  }

  @Test
  void filteredEndpointsAndChangingEligibilityVersionsCannotBecomeSuccessfulObservations() {
    for (int missing : List.of(1, 2, 3)) {
      var calls = new AtomicInteger();
      conflict(
          () ->
              reader(CollectionEdgeMutation.Mode.LINK, access(Set.of()), access(Set.of()))
                  .read(
                      plan -> {
                        int index = calls.incrementAndGet();
                        return index == missing
                            ? rows(List.of())
                            : rows(List.of(List.of(index == 1 ? id(1) : id(2), 0L)));
                      }));
      assertThat(calls.get()).isEqualTo(missing);
    }
    var calls = new AtomicInteger();
    conflict(
        () ->
            reader(CollectionEdgeMutation.Mode.LINK, access(Set.of()), access(Set.of()))
                .read(
                    plan -> {
                      int index = calls.incrementAndGet();
                      return rows(
                          List.of(List.of(index == 1 ? id(1) : id(2), index == 3 ? 1L : 0L)));
                    }));
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void maskedConditionFieldsCannotBeUsedForQueryInference() {
    var calls = new AtomicInteger();
    conflict(
        () ->
            reader(
                    CollectionEdgeMutation.Mode.LINK,
                    access(Set.of("renamed_status")),
                    access(Set.of()))
                .read(
                    plan -> {
                      calls.incrementAndGet();
                      return rows(List.of());
                    }));
    assertThat(calls.get()).isZero();
    // Target eligibility is not consulted for unlink; target identity authorization remains.
    var unlink =
        reader(
            CollectionEdgeMutation.Mode.UNLINK, access(Set.of()), access(Set.of("renamed_status")));
    unlink.read(
        plan -> {
          int index = calls.incrementAndGet();
          return index == 3
              ? rows(List.of())
              : rows(List.of(List.of(index == 1 ? id(1) : id(2), 0L)));
        });
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void corruptCountsDuplicateIdentitiesAndInexactVersionsFailClosed() {
    for (var value :
        List.of(-1L, new BigDecimal("0.1"), new BigDecimal("9223372036854775808"), "0")) {
      assertThatThrownBy(
              () ->
                  RecordCollectionObservationReader.versions(
                      Set.of(id(1)), rows(List.of(List.of(id(1), value)))))
          .isInstanceOf(DnaRuntimeException.class);
    }
    for (var query :
        List.of(
            rows(List.of(List.of(id(2), 0L))),
            rows(List.of(List.of(id(1), 0L), List.of(id(1), 0L))),
            new CalciteQueryResult(List.of(), List.of(List.of(id(1), 0L)), true, 1, 0, null),
            new CalciteQueryResult(List.of(), List.of(List.of(id(1), 0L)), false, 0, 0, null))) {
      assertThatThrownBy(() -> RecordCollectionObservationReader.versions(Set.of(id(1)), query))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThat(
            RecordCollectionObservationReader.versions(
                Set.of(id(1)), rows(List.of(List.of(id(1), new BigDecimal("3.0"))))))
        .containsEntry(id(1), 3L);
  }

  private RecordCollectionObservationReader reader(
      CollectionEdgeMutation.Mode mode, RecordAccessContext source, RecordAccessContext target) {
    var command =
        new CollectionRecordCommand(
            "1",
            "projects",
            new RecordSchemaReference(1, "a".repeat(64)),
            "members",
            id(1),
            0L,
            List.of(new CollectionEdgeMutation(id(2), 0L, -1L, mode)),
            "observe",
            30_000);
    return new RecordCollectionObservationReader(
        binding, command, RecordCollectionPublicationTest.rule(binding), source, target, compiler);
  }

  private static RecordAccessContext access(Set<String> masked) {
    return new RecordAccessContext(
        new RuntimeActor("tenant", "actor"),
        "member-page",
        Set.of("id", "renamed_status"),
        Set.of(),
        masked,
        new DataScopeCondition("ALL", "created_org_dept_id", "created_by", "actor", Set.of()),
        "department");
  }

  private static CalciteQueryResult rows(List<List<Object>> rows) {
    return new CalciteQueryResult(List.of(), rows, false, rows.size(), 0, null);
  }

  private static String id(int value) {
    return new UUID(0, value).toString();
  }

  private static void conflict(Runnable work) {
    assertThatThrownBy(work::run)
        .isInstanceOfSatisfying(
            DnaRuntimeException.class,
            error ->
                assertThat(error.errorCode())
                    .isEqualTo(DnaRuntimeErrorCode.RECORD_REFERENCE_CONFLICT));
  }
}
