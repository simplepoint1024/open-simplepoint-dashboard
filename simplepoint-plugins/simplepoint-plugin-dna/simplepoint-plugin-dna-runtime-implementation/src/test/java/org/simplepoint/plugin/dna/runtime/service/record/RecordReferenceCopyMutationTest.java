package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;

class RecordReferenceCopyMutationTest {
  private static final String RECORD = "11111111-1111-1111-1111-111111111111";
  private final RecordReferenceCopyStore.Key key =
      new RecordReferenceCopyStore.Key("tenant", "model", RECORD);

  @Test
  void protectedEffectsRequireTheExactBusinessModelTenantRecordAndResultVersion() {
    var copy =
        new RecordReferenceCopyMutation(
            key, null, 0, List.of(RecordReferenceCopyStoreTest.origin()));
    var source = statement("model", "tenant", RECORD, 0, RecordMutationType.CREATE);
    var prepared =
        new RecordWritePreparation(List.of(source), null, 1, List.of(), null, List.of(copy));
    assertThat(prepared.receiptCount()).isEqualTo(1);
    assertThat(prepared.statements()).containsExactly(source);
    assertThat(prepared.copies()).containsExactly(copy);
    for (var wrong :
        List.of(
            statement("foreign", "tenant", RECORD, 0, RecordMutationType.CREATE),
            statement("model", "foreign", RECORD, 0, RecordMutationType.CREATE),
            statement(
                "model",
                "tenant",
                "22222222-2222-2222-2222-222222222222",
                0,
                RecordMutationType.CREATE),
            statement("model", "tenant", RECORD, 1, RecordMutationType.UPDATE),
            statement("model", "tenant", RECORD, 0, RecordMutationType.DELETE),
            new RecordSqlStatement(
                "INSERT protected",
                List.of(),
                RecordMutationType.CREATE,
                RECORD,
                0,
                Instant.EPOCH))) {
      assertThatThrownBy(
              () ->
                  new RecordWritePreparation(
                      List.of(wrong), null, 1, List.of(), null, List.of(copy)))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThatThrownBy(() -> copy.verify(List.of(source, source)))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                copy.verify(
                    List.of(
                        source,
                        statement("model", "tenant", RECORD, 1, RecordMutationType.UPDATE))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                copy.verify(
                    List.of(
                        source,
                        statement("model", "tenant", RECORD, 1, RecordMutationType.DELETE))))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                new RecordWritePreparation(
                    List.of(source), null, 1, List.of(), null, List.of(copy, copy)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void metadataActorComesFromTheDurableOperationAndCannotBorrowAnotherTenant() {
    var copy = new RecordReferenceCopyMutation(key, null, 0, List.of());
    var operation =
        new RecordOperationIdentity(
            "operation", "tenant", "actor", "parent-model", "key", "a".repeat(64));
    var compiled = copy.statement(operation, Instant.EPOCH);
    assertThat(compiled.subject()).isNull();
    assertThat(compiled.parameters()).contains("actor", "tenant", "model", RECORD);
    assertThatThrownBy(() -> copy.statement(null, Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                copy.statement(
                    new RecordOperationIdentity(
                        "operation", "foreign", "actor", "parent-model", "key", "a".repeat(64)),
                    Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void unkeyedEffectsUseTheCompilerActorAndRejectIdentityDisagreement() {
    var copy = new RecordReferenceCopyMutation(key, null, 0, List.of());
    var plain = statement("model", "tenant", RECORD, 0, RecordMutationType.CREATE);
    var source =
        new RecordSqlStatement(
            plain.sql(),
            plain.parameters(),
            plain.mutationType(),
            plain.recordId(),
            plain.resultVersion(),
            plain.updatedAt(),
            new RecordSqlStatement.Subject("model", "tenant", "compiler-actor"));
    assertThat(copy.statement(null, List.of(source), Instant.EPOCH).parameters())
        .contains("compiler-actor");
    assertThatThrownBy(() -> copy.statement(null, List.of(plain), Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
    assertThatThrownBy(
            () ->
                copy.statement(
                    new RecordOperationIdentity(
                        "operation", "tenant", "another-actor", "model", "key", "a".repeat(64)),
                    List.of(source),
                    Instant.EPOCH))
        .isInstanceOf(DnaRuntimeException.class);
  }

  @Test
  void originVersionsCannotMoveBackwardsOrReuseTheSameCommittedVersion() {
    var previous = new RecordReferenceCopyStore.Stored(3, List.of());
    for (long version : new long[] {-1, 2, 3}) {
      assertThatThrownBy(() -> new RecordReferenceCopyMutation(key, previous, version, List.of()))
          .isInstanceOf(DnaRuntimeException.class);
    }
    assertThat(new RecordReferenceCopyMutation(key, previous, 4, List.of()).sourceVersion())
        .isEqualTo(4);
  }

  private static RecordSqlStatement statement(
      String model, String tenant, String id, long version, RecordMutationType type) {
    return new RecordSqlStatement(
        "business mutation",
        List.of(),
        type,
        id,
        version,
        Instant.EPOCH,
        new RecordSqlStatement.Subject(model, tenant));
  }
}
