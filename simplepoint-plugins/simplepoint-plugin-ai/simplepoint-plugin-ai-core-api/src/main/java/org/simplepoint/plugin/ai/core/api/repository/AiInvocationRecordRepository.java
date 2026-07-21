package org.simplepoint.plugin.ai.core.api.repository;

import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;

/** Repository contract for the AI invocation usage ledger. */
public interface AiInvocationRecordRepository extends BaseRepository<AiInvocationRecord, String> {
}
