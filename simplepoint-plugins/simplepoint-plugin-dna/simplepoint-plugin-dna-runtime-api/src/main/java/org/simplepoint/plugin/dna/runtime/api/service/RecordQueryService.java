package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.record.RecordCountQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordLookupQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageQuery;
import org.simplepoint.plugin.dna.runtime.api.record.RecordPageView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordView;

/** Authorized structured reads for published physical models. */
public interface RecordQueryService {

  /** Returns one stable server page. */
  RecordPageView page(RecordPageQuery query);

  /** Returns the count produced from the same predicate contract as paging. */
  long count(RecordCountQuery query);

  /** Returns one authorized active record or a stable not-found error. */
  RecordView get(RecordLookupQuery query);
}
