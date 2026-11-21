package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPermissionModel;

class RecordReferenceSnapshotDatabaseTest {
  @AfterEach
  void clearAuthority() {
    ResourcePolicyContext.clear();
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void snapshotAndReplayRetainCommitTimeLabelAfterTargetChanges(boolean postgres) throws Exception {
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(
            postgres, new DefaultCalciteQueryEngine(), null, true)) {
      String customer = f.customer("客户-日本語");
      var operation = capture(f, customer);
      var first = read(f, operation);
      assertThat(first.contractVersion()).isEqualTo(5);
      assertThat(first.labelOrigins())
          .singleElement()
          .satisfies(
              origin -> {
                assertThat(origin.requirements())
                    .singleElement()
                    .satisfies(
                        required -> {
                          assertThat(required.recordId()).isEqualTo(customer);
                          assertThat(required.fieldIds()).containsExactly("status");
                        });
              });
      assertThat(first.values().referenceSnapshots()).hasSize(1);
      var label = first.values().referenceSnapshots().getFirst();
      assertThat(label.label()).isEqualTo("客户-日本語");
      assertThat(label.targetRecordId()).isEqualTo(customer);
      assertThat(label.targetVersion()).isZero();
      f.base.save(
          f.base.customer,
          f.base.update(f.base.customer, customer, 0, Map.of("status", "Changed")));
      var replay =
          f.base.backend.executePreparedOwned(
              f.base.trade,
              List.of(),
              new RecordWriteAdmission("tenant-a", 1),
              30_000,
              operation,
              (reader, at, installed) -> {
                throw new AssertionError("Committed operation must not recapture");
              });
      assertThat(replay.replayed()).isTrue();
      assertThat(read(f, operation)).isEqualTo(first);
      assertThat(f.base.value(f.base.customer, customer, "status")).isEqualTo("Changed");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void maskedTargetLabelIsNotCopiedIntoSourceSnapshot(boolean postgres) throws Exception {
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(
            postgres, new DefaultCalciteQueryEngine(), null, true)) {
      String customer = f.customer("private label");
      f.authorize(
          true,
          "ALL",
          Map.of(
              RecordPermissionModel.key(f.base.customer.physicalModelId())
                  + "#"
                  + RecordPermissionModel.field("status"),
              "MASKED"));
      var operation = capture(f, customer);
      assertThat(read(f, operation).values().referenceSnapshots()).isEmpty();
      assertThat(f.base.count(f.base.trade)).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void labelReadFailureRollsBackSourceMutationAndReceipt(boolean postgres) throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    var fail = new AtomicBoolean(false);
    doAnswer(
            invocation -> {
              CalciteQueryRequest query = invocation.getArgument(0);
              if (fail.get()
                  && query.sql().contains("ref_customer_")
                  && query.sql().contains("record_version")) {
                throw new IllegalStateException("Injected snapshot read failure");
              }
              return invocation.callRealMethod();
            })
        .when(engine)
        .execute(any(), any());
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(postgres, engine, null, true)) {
      String customer = f.customer("original");
      fail.set(true);
      var attempt = prepare(f, customer);
      assertThatThrownBy(() -> execute(f, attempt)).isInstanceOf(RuntimeException.class);
      assertThat(f.base.count(f.base.trade)).isZero();
      assertThat(read(f, attempt.operation())).isNull();
      var receipt =
          f.base.backend.read(
              f.base.trade,
              List.of(),
              f.base.access(f.base.trade),
              30_000,
              session -> session.receipt(attempt.operation()));
      assertThat(receipt).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void historicalLabelsFollowCurrentTargetScopeWithoutReplacingOriginalText(boolean postgres)
      throws Exception {
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(
            postgres, new DefaultCalciteQueryEngine(), null, true)) {
      String customer = f.customer("Original customer");
      var operation = capture(f, customer);
      var projected =
          f.base.backend.read(
              f.base.trade,
              List.of(),
              f.base.access(f.base.trade),
              30_000,
              session -> {
                var stored = session.snapshot(operation);
                var values = stored.values();
                var labels =
                    RecordReferenceSnapshotProvenance.visible(
                        f.base.trade,
                        List.of(),
                        values,
                        values.referenceSnapshots(),
                        session.historical(stored));
                return new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView(
                    values.parent(), values.details(), labels);
              });
      f.base.save(
          f.base.customer,
          f.base.update(f.base.customer, customer, 0, Map.of("status", "Renamed")));
      var actor = f.base.access(f.base.trade).actor();
      f.authorize(true, "ALL", Map.of());
      assertThat(
              f.historyReader
                  .authorize(actor, projected, new RecordExecutionDeadline(30_000))
                  .referenceSnapshots())
          .extracting(org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceSnapshot::label)
          .containsExactly("Original customer");
      f.authorize(false, "ALL", Map.of());
      assertThat(
              f.historyReader
                  .authorize(actor, projected, new RecordExecutionDeadline(30_000))
                  .referenceSnapshots())
          .isEmpty();
      f.authorize(true, "DENY", Map.of());
      assertThat(
              f.historyReader
                  .authorize(actor, projected, new RecordExecutionDeadline(30_000))
                  .referenceSnapshots())
          .isEmpty();
      f.authorize(
          true,
          "ALL",
          Map.of(
              RecordPermissionModel.key(f.base.customer.physicalModelId())
                  + "#"
                  + RecordPermissionModel.field("status"),
              "MASKED"));
      assertThat(
              f.historyReader
                  .authorize(actor, projected, new RecordExecutionDeadline(30_000))
                  .referenceSnapshots())
          .isEmpty();
      f.authorize(true, "ALL", Map.of());
      assertThat(
              f.historyReader
                  .authorize(actor, projected, new RecordExecutionDeadline(30_000))
                  .referenceSnapshots())
          .isEqualTo(projected.referenceSnapshots());
      assertThat(read(f, operation).values()).isEqualTo(projected);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void ownedServiceRechecksTargetAuthorityOnEverySnapshotRead(boolean postgres) throws Exception {
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(
            postgres, new DefaultCalciteQueryEngine(), null, true)) {
      String customer = f.customer("Captured through aggregate");
      var command =
          new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand(
              f.command(
                  UUID.randomUUID().toString(),
                  List.of(new RecordCreateMutation("create", Map.of("customer_id", customer)))),
              List.of(),
              new org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordSnapshotRequest(
                  List.of()));
      var receipt = f.ownedService.execute(command);
      var original = f.ownedService.snapshot(command);
      assertThat(original.parent().id()).isEqualTo(receipt.recordId());
      assertThat(original.referenceSnapshots()).hasSize(1);
      assertThat(original.referenceSnapshots().getFirst().label())
          .isEqualTo("Captured through aggregate");
      f.base.save(
          f.base.customer,
          f.base.update(f.base.customer, customer, 0, Map.of("status", "Current name")));
      f.authorize(false, "ALL", Map.of());
      var revoked = f.ownedService.snapshot(command);
      assertThat(revoked.parent()).isEqualTo(original.parent());
      assertThat(revoked.referenceSnapshots()).isEmpty();
      f.authorize(true, "ALL", Map.of());
      assertThat(f.ownedService.snapshot(command)).isEqualTo(original);
      assertThat(f.ownedService.execute(command).recordId()).isEqualTo(receipt.recordId());
      assertThat(f.ownedService.snapshot(command)).isEqualTo(original);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void identityOnlyVisibilityQueryRequiresCurrentLabelAndRowPermission(boolean postgres)
      throws Exception {
    try (var f =
        new RecordReferenceQualificationDatabaseTest.Fixture(
            postgres, new DefaultCalciteQueryEngine(), null, true)) {
      String customer = f.customer("Current sensitive label");
      var query =
          new org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceVisibilityQuery(
              f.base.customer.physicalModelId(),
              f.schema,
              "status",
              List.of(customer, UUID.randomUUID().toString()),
              30_000);
      f.authorize(true, "ALL", Map.of());
      ResourcePolicyContext.set("customers");
      assertThat(f.queries.visibleReferences(query)).containsExactly(customer);
      f.authorize(true, "DENY", Map.of());
      ResourcePolicyContext.set("customers");
      assertThat(f.queries.visibleReferences(query)).isEmpty();
      f.authorize(
          true,
          "ALL",
          Map.of(
              RecordPermissionModel.key(f.base.customer.physicalModelId())
                  + "#"
                  + RecordPermissionModel.field("status"),
              "MASKED"));
      ResourcePolicyContext.set("customers");
      assertThat(f.queries.visibleReferences(query)).isEmpty();
    }
  }

  private static RecordOperationIdentity capture(
      RecordReferenceQualificationDatabaseTest.Fixture f, String customer) {
    var attempt = prepare(f, customer);
    execute(f, attempt);
    return attempt.operation();
  }

  private static Attempt prepare(
      RecordReferenceQualificationDatabaseTest.Fixture f, String customer) {
    var statement = f.base.create(f.base.trade, Map.of("customer_id", customer));
    var access = f.base.access(f.base.trade);
    var operation =
        RecordOperationIdentity.of(
            access.actor(),
            f.command(
                UUID.randomUUID().toString(),
                List.of(new RecordCreateMutation("create", Map.of("customer_id", customer)))),
            "f".repeat(64));
    var select =
        f.base.compiler.lookup(
            f.base.trade,
            access,
            new RecordLookupQuery(
                f.base.trade.physicalModelId(), f.schema, statement.recordId(), List.of()));
    var snapshot =
        new RecordSnapshotPlan(
            statement.recordId(),
            statement.resultVersion(),
            List.of(new RecordSnapshotPlan.Part(f.base.trade, access, select, true)),
            new RecordSnapshotValidation(f.base.trade, access, List.of()));
    var prepared =
        new RecordWritePreparation(
            List.of(statement),
            snapshot,
            1,
            List.of(
                new RecordReferenceCheck(
                    "trade-customer",
                    f.base.trade.physicalModelId(),
                    statement.recordId(),
                    customer,
                    true)));
    return new Attempt(operation, prepared);
  }

  private static void execute(RecordReferenceQualificationDatabaseTest.Fixture f, Attempt attempt) {
    f.base.backend.executePreparedOwned(
        f.base.trade,
        List.of(),
        new RecordWriteAdmission("tenant-a", 1),
        30_000,
        attempt.operation(),
        (reader, at, installed) -> attempt.prepared());
  }

  private static RecordStoredSnapshot read(
      RecordReferenceQualificationDatabaseTest.Fixture f, RecordOperationIdentity operation) {
    return f.base.backend.read(
        f.base.trade,
        List.of(),
        f.base.access(f.base.trade),
        30_000,
        session -> session.snapshot(operation));
  }

  private record Attempt(RecordOperationIdentity operation, RecordWritePreparation prepared) {}
}
