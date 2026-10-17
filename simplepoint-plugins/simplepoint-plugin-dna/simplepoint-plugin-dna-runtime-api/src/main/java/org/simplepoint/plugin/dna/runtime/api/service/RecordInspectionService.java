package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCompletenessView;

/** Owner-management data checks, separate from authorized end-user record browsing. */
public interface RecordInspectionService {

  /**
   * Counts missing values across tenant-owned, non-deleted records matching the bounded filter.
   * Requires management authority and matching application/form ownership. STRING/TEXT are empty
   * when null or composed solely of ECMAScript trim whitespace; other types are empty only when null.
   * No write barrier is established. A caller must coordinate policy activation separately.
   */
  RecordCompletenessView inspectCompleteness(RecordCompletenessQuery query);
}
