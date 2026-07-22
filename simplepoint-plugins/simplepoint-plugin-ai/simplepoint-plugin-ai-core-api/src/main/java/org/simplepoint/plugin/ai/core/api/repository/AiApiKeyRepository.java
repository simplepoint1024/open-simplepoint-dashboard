package org.simplepoint.plugin.ai.core.api.repository;

import java.time.Instant;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiApiKey;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/** Persistence contract for model gateway API keys. */
public interface AiApiKeyRepository extends BaseRepository<AiApiKey, String> {

  /** Finds a non-deleted key by identifier. */
  Optional<AiApiKey> findActiveById(String id);

  /** Finds a non-deleted key by its public lookup prefix. */
  Optional<AiApiKey> findActiveByPrefix(String keyPrefix);

  /** Checks whether another active key has the same name in the requested scope. */
  boolean existsActiveByNameAndScope(
      String name,
      AiResourceScope scopeType,
      String tenantId,
      String excludeId
  );

  /** Updates usage metadata after a key has authenticated one public request. */
  void recordUsage(String id, Instant usedAt);
}
