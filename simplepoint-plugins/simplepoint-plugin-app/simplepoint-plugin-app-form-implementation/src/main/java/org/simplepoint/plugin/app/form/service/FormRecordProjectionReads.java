package org.simplepoint.plugin.app.form.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Bounded DNA projections, joined only when every batch describes the same record revisions. */
final class FormRecordProjectionReads {
  private FormRecordProjectionReads() {
  }

  static RecordView record(final List<String> fields, final Function<List<String>, RecordView> read) {
    RecordView result = null;
    for (var batch : batches(fields)) {
      var next = read.apply(batch);
      result = result == null ? next : merge(result, next);
    }
    return result;
  }

  static RecordPageView page(final List<String> fields, final Function<List<String>, RecordPageView> read) {
    RecordPageView result = null;
    for (var batch : batches(fields)) {
      var next = read.apply(batch);
      if (result == null) {
        result = next;
        continue;
      }
      if (result.total() != next.total() || result.page() != next.page() || result.size() != next.size()
          || result.records().size() != next.records().size()) {
        throw changed();
      }
      var records = new ArrayList<RecordView>();
      for (int index = 0; index < result.records().size(); index++) {
        records.add(merge(result.records().get(index), next.records().get(index)));
      }
      result = new RecordPageView(result.page(), result.size(), result.total(), records);
    }
    return result;
  }

  private static RecordView merge(final RecordView first, final RecordView next) {
    if (!first.id().equals(next.id()) || first.version() != next.version()) {
      throw changed();
    }
    var values = new LinkedHashMap<>(first.values());
    next.values().forEach((key, value) -> {
      if (values.containsKey(key) && !java.util.Objects.equals(values.get(key), value)) {
        throw changed();
      }
      values.put(key, value);
    });
    return new RecordView(first.id(), first.version(), values);
  }

  private static List<List<String>> batches(final List<String> fields) {
    var data = fields.stream().filter(field -> !field.equals(FormStorageProjectionCompiler.RECORD_STATE_CODE)).toList();
    var result = new ArrayList<List<String>>();
    for (int start = 0; start < Math.max(data.size(), 1); start += 99) {
      var batch = new ArrayList<>(data.subList(start, Math.min(start + 99, data.size())));
      batch.add(FormStorageProjectionCompiler.RECORD_STATE_CODE);
      result.add(List.copyOf(batch));
    }
    return result;
  }

  private static ApplicationFormException changed() {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_CONFLICT, "Records changed while reading; refresh and retry");
  }
}
