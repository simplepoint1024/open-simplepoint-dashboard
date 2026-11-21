package org.simplepoint.plugin.dna.runtime.api.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordReferenceVisibilityQueryTest {
  private static final RecordSchemaReference SCHEMA = new RecordSchemaReference(1, "a".repeat(64));

  @Test
  void canonicalIdentitiesAndRemainingBudgetSurviveTransport() throws Exception {
    String id = UUID.randomUUID().toString();
    var query =
        new RecordReferenceVisibilityQuery(
            "model", SCHEMA, "name", List.of(id, id.toUpperCase()), 127);
    assertThat(query.recordIds()).containsExactly(id);
    var mapper = new ObjectMapper();
    assertThat(
            mapper.readValue(mapper.writeValueAsBytes(query), RecordReferenceVisibilityQuery.class))
        .isEqualTo(query);
  }

  @Test
  void invalidIdentitiesAndUnboundedRequestsAreRejected() {
    for (var ids :
        List.of(
            List.<String>of(),
            List.of("arbitrary"),
            List.of(" " + UUID.randomUUID()),
            java.util.Collections.nCopies(101, UUID.randomUUID().toString()))) {
      assertThatThrownBy(
              () -> new RecordReferenceVisibilityQuery("model", SCHEMA, "name", ids, 1000))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int timeout : List.of(0, -1, 30_001)) {
      assertThatThrownBy(
              () ->
                  new RecordReferenceVisibilityQuery(
                      "model", SCHEMA, "name", List.of(UUID.randomUUID().toString()), timeout))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
