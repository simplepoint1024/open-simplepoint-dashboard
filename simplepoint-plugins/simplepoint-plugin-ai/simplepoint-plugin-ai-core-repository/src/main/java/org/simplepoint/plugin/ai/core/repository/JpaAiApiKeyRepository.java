package org.simplepoint.plugin.ai.core.repository;

import java.time.Instant;
import java.util.Optional;
import org.simplepoint.data.jpa.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiApiKeyRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JPA persistence for model gateway API keys. */
@Repository
public interface JpaAiApiKeyRepository
    extends BaseRepository<AiApiKey, String>, AiApiKeyRepository {

  @Override
  @Query("select k from AiApiKey k where k.id = :id and k.deletedAt is null")
  Optional<AiApiKey> findActiveById(@Param("id") String id);

  @Override
  @Query("select k from AiApiKey k where k.keyPrefix = :keyPrefix and k.deletedAt is null")
  Optional<AiApiKey> findActiveByPrefix(@Param("keyPrefix") String keyPrefix);

  @Override
  @Query("""
      select (count(k) > 0) from AiApiKey k
      where lower(k.name) = lower(:name)
        and k.scopeType = :scopeType
        and ((:tenantId is null and k.tenantId is null) or k.tenantId = :tenantId)
        and (:excludeId is null or k.id <> :excludeId)
        and k.deletedAt is null
      """)
  boolean existsActiveByNameAndScope(
      @Param("name") String name,
      @Param("scopeType") AiResourceScope scopeType,
      @Param("tenantId") String tenantId,
      @Param("excludeId") String excludeId
  );

  @Override
  @Modifying
  @Transactional
  @Query("""
      update AiApiKey k
      set k.lastUsedAt = :usedAt, k.usageCount = coalesce(k.usageCount, 0) + 1
      where k.id = :id and k.deletedAt is null
      """)
  void recordUsage(@Param("id") String id, @Param("usedAt") Instant usedAt);
}
