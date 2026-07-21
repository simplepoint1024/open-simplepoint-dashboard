package org.simplepoint.plugin.ai.core.repository;

import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.repository.AiInvocationRecordRepository;
import org.springframework.stereotype.Repository;

/** JPA repository for AI invocation records. */
@Repository
public interface JpaAiInvocationRecordRepository
    extends BaseRepository<AiInvocationRecord, String>, AiInvocationRecordRepository {
}
