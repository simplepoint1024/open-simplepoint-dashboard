package org.simplepoint.plugin.ai.runtime.api.repository;

import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.runtime.api.entity.AiRuntimeSecret;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for encrypted Runtime secret references.
 */
public interface AiRuntimeSecretRepository
    extends BaseRepository<AiRuntimeSecret, String> {

  /**
   * Finds one non-deleted secret by identifier.
   */
  Optional<AiRuntimeSecret> findActiveById(String id);

  /**
   * Finds one active secret code in the exact platform or tenant scope.
   */
  Optional<AiRuntimeSecret> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );

  /**
   * Pages active secrets visible in the exact platform or tenant scope.
   */
  Page<AiRuntimeSecret> findAllActiveByScope(
      AiResourceScope scopeType,
      String tenantId,
      Pageable pageable
  );
}
