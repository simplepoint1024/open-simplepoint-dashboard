package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.core.ResourcePolicyContext;
import org.simplepoint.data.calcite.core.query.CalciteUpdateRequest;
import org.simplepoint.data.calcite.core.query.DefaultCalciteQueryEngine;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;

/** A real Calcite sequence with an injected driver diagnostic after a successful physical write. */
class RecordSequenceContentionDatabaseTest {
  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
    ResourcePolicyContext.clear();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void retryKeepsGeneratedIdentityClockVersionsAndOnlyCommittedReceipts(boolean postgres)
      throws Exception {
    var engine = spy(new DefaultCalciteQueryEngine());
    try (var f = new RecordReferenceQualificationDatabaseTest.Fixture(postgres, engine)) {
      String target = f.customer("ACTIVE");
      var existing =
          f.execute(
              new RecordCreateMutation(
                  "existing", Map.of("customer_id", target, "status", "DRAFT")));
      String table = f.base.trade.target().metadataTarget().tableName();
      var attempts = new AtomicInteger();
      var inserts = new ArrayList<CalciteUpdateRequest>();
      doAnswer(
              call -> {
                var request = (CalciteUpdateRequest) call.getArgument(0);
                Object result = call.callRealMethod();
                if (request.sql().startsWith("INSERT") && request.sql().contains(table)) {
                  inserts.add(request);
                  if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException(
                        "Injected transient diagnostic after physical insert",
                        new SQLException(
                            "Opaque", postgres ? "40P01" : "40001", postgres ? 0 : 1213));
                  }
                }
                return result;
              })
          .when(engine)
          .executeUpdate(any(), any());
      var command =
          f.command(
              "retry-sequence",
              List.of(
                  new RecordCreateMutation(
                      "created", Map.of("customer_id", target, "status", "DRAFT")),
                  new RecordUpdateMutation(
                      "edit", existing.recordId(), 0, Map.of("note", "changed")),
                  new RecordUpdateMutation(
                      "submit", existing.recordId(), 1, Map.of("status", "SUBMITTED"))));
      var result = f.service.execute(command);
      assertThat(attempts).hasValue(2);
      assertThat(inserts).hasSize(2);
      assertThat(inserts.getFirst().sql()).isEqualTo(inserts.getLast().sql());
      assertThat(inserts.getFirst().parameters()).isEqualTo(inserts.getLast().parameters());
      assertThat(result.results())
          .extracting(value -> value.result().version())
          .containsExactly(0L, 1L, 2L);
      assertThat(result.results())
          .extracting(value -> value.reference())
          .containsExactly("created", "edit", "submit");
      assertThat(f.base.count(f.base.trade)).isEqualTo(2);
      assertThat(f.base.value(f.base.trade, existing.recordId(), "record_version")).isEqualTo(2L);
      assertThat(f.service.execute(command).results()).isEqualTo(result.results());
      assertThat(attempts).hasValue(2);
    }
  }
}
