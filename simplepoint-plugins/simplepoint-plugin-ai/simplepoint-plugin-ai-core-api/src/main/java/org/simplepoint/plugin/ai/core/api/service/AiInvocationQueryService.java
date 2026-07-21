package org.simplepoint.plugin.ai.core.api.service;

import java.util.Map;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read-only query contract for the metadata-only invocation ledger. */
public interface AiInvocationQueryService extends BaseService<AiInvocationRecord, String> {

  /** Pages ledger records visible in the current management scope. */
  <S extends AiInvocationRecord> Page<S> limit(
      Map<String, String> attributes,
      Pageable pageable
  );
}
