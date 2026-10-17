package org.simplepoint.plugin.app.form.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

class FormRecordProjectionReadsTest {
  @Test
  void widePagesKeepExactValuesAndReadEachProjectionWithinTheDnaLimit() {
    var fields = IntStream.range(0, 300).mapToObj(index -> "field_" + index).toList();
    var calls = new AtomicInteger();
    var page = FormRecordProjectionReads.page(fields, batch -> {
      calls.incrementAndGet();
      assertThat(batch).hasSizeLessThanOrEqualTo(100).contains("record_state");
      Map<String, Object> values = new LinkedHashMap<>();
      batch.forEach(field -> values.put(field, "9007199254740993"));
      values.put("record_state", "SUBMITTED");
      return new RecordPageView(0, 20, 1, List.of(new RecordView("record-a", 3, values)));
    });
    assertThat(calls.get()).isEqualTo(4);
    assertThat(page.records().getFirst().values()).hasSize(301).containsEntry("field_299", "9007199254740993");
  }

  @Test
  void concurrentWritesAndPageShiftsCannotMixRecordRevisions() {
    var fields = IntStream.range(0, 120).mapToObj(index -> "field_" + index).toList();
    var calls = new AtomicInteger();
    assertThatThrownBy(() -> FormRecordProjectionReads.record(fields, batch ->
        new RecordView("record-a", calls.incrementAndGet(), Map.of("record_state", "DRAFT"))))
        .isInstanceOf(ApplicationFormException.class);
    calls.set(0);
    assertThatThrownBy(() -> FormRecordProjectionReads.page(fields, batch -> new RecordPageView(0, 20, 2,
        List.of(new RecordView("record-" + calls.incrementAndGet(), 1, Map.of("record_state", "DRAFT"))))))
        .isInstanceOf(ApplicationFormException.class);
  }
}
