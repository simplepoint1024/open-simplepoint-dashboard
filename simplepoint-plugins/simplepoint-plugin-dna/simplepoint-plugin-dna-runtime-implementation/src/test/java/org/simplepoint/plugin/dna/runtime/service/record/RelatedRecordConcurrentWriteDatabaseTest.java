package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.data.calcite.core.query.CalciteQueryRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RelatedRecordScope;

/** Commits an independent normal write exactly between inverse membership read and mutation. */
class RelatedRecordConcurrentWriteDatabaseTest {
  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void staleMembershipCannotOverwriteCommittedRebind(boolean postgres) throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    try (var fixture = new RecordReferenceQualificationDatabaseTest.Fixture(postgres, engine)) {
      String original = fixture.customer("ACTIVE");
      final String replacement = fixture.customer("ACTIVE");
      var saved =
          fixture.execute(
              new RecordCreateMutation(
                  "create", Map.of("customer_id", original, "status", "DRAFT")));
      var interleaved = new AtomicBoolean();
      doAnswer(
              call -> {
                var query = (CalciteQueryRequest) call.getArgument(0);
                var result = call.callRealMethod();
                var parameters =
                    query.parameters() == null
                        ? List.<String>of()
                        : query.parameters().stream().map(String::valueOf).toList();
                if (parameters.contains(original)
                    && parameters.contains(saved.recordId())
                    && interleaved.compareAndSet(false, true)) {
                  // A second platform transaction commits while the first still holds its old read.
                  fixture.execute(
                      new RecordUpdateMutation(
                          "rebind", saved.recordId(), 0, Map.of("customer_id", replacement)));
                }
                return result;
              })
          .when(engine)
          .execute(any(), any());
      var command =
          new RelatedRecordCommand(
              new OwnedRecordCommand(
                  fixture.command(
                      "stale-inverse-edit",
                      List.of(
                          new RecordUpdateMutation(
                              "update", saved.recordId(), 0, Map.of("note", "must not commit")))),
                  List.of()),
              new RelatedRecordScope(
                  "1",
                  "trade-customer",
                  fixture.base.customer.physicalModelId(),
                  original,
                  RelatedRecordScope.Mode.MAINTAIN,
                  null));
      assertThatThrownBy(() -> fixture.ownedService.executeRelated(command))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              e ->
                  assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));
      assertThat(interleaved).isTrue();
      assertThat(fixture.base.value(fixture.base.trade, saved.recordId(), "customer_id"))
          .isEqualTo(replacement);
      assertThat(fixture.base.value(fixture.base.trade, saved.recordId(), "record_version"))
          .isEqualTo(1L);
      assertThat(fixture.base.value(fixture.base.trade, saved.recordId(), "note")).isNull();
      assertThatThrownBy(() -> fixture.ownedService.executeRelated(command))
          .isInstanceOfSatisfying(
              DnaRuntimeException.class,
              e ->
                  assertThat(e.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_VERSION_CONFLICT));
    }
  }
}
