package org.simplepoint.plugin.dna.runtime.api.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelatedRecordPageQueryTest {
  private final RecordPageQuery page =
      new RecordPageQuery(
          "source",
          new RecordSchemaReference(1, "a".repeat(64)),
          1,
          50,
          List.of(),
          null,
          List.of());

  @Test
  void preservesExactTargetModelAndNormalizesOnlyCanonicalUuidCase() throws Exception {
    var query =
        new RelatedRecordPageQuery(
            page, "relation", "target", "ABCDEFAB-1234-1234-1234-ABCDEFABCDEF");
    assertEquals("abcdefab-1234-1234-1234-abcdefabcdef", query.targetRecordId());
    var mapper = new ObjectMapper();
    assertEquals(
        query, mapper.readValue(mapper.writeValueAsBytes(query), RelatedRecordPageQuery.class));
    assertEquals("target", query.targetModelId());
  }

  @Test
  void refusesUnsavedAbbreviatedOrMalformedIdentities() {
    for (String id : List.of("", "draft", "1-1-1-1-1", "00000000-0000-0000-0000-000000000001 ")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new RelatedRecordPageQuery(page, "relation", "target", id));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelatedRecordPageQuery(
                page, "../relation", "target", "00000000-0000-0000-0000-000000000001"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelatedRecordPageQuery(
                page, "relation", "", "00000000-0000-0000-0000-000000000001"));
  }
}
