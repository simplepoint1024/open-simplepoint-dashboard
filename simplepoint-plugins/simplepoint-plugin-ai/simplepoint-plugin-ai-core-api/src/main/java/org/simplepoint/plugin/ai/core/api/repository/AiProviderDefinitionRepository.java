package org.simplepoint.plugin.ai.core.api.repository;

import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;

/**
 * Repository contract for AI provider configurations.
 */
public interface AiProviderDefinitionRepository extends BaseRepository<AiProviderDefinition, String> {

  /**
   * Finds an active provider by id.
   *
   * @param id provider id
   * @return provider
   */
  Optional<AiProviderDefinition> findActiveById(String id);

  /**
   * Finds an active provider code inside one ownership scope.
   *
   * @param code provider code
   * @param scopeType ownership scope
   * @param tenantId tenant id for tenant scope
   * @return provider
   */
  Optional<AiProviderDefinition> findActiveByCodeAndScope(
      String code,
      AiResourceScope scopeType,
      String tenantId
  );

  /**
   * Finds enabled providers that opted into automatic model synchronization.
   *
   * @return providers
   */
  List<AiProviderDefinition> findAllAutoSyncEnabled();
}
