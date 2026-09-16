package org.simplepoint.plugin.ai.core.api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.simplepoint.api.base.BaseRepository;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Repository contract for AI model definitions.
 */
public interface AiModelDefinitionRepository extends BaseRepository<AiModelDefinition, String> {

  /**
   * Finds an active model by id.
   *
   * @param id model id
   * @return model
   */
  Optional<AiModelDefinition> findActiveById(String id);

  /** Finds all requested non-deleted models in one round trip. */
  List<AiModelDefinition> findAllActiveByIdIn(Collection<String> ids);

  /**
   * Finds an active model by provider and remote model identifier.
   *
   * @param providerId provider id
   * @param modelId remote model id
   * @return model
   */
  Optional<AiModelDefinition> findActiveByProviderAndModelId(String providerId, String modelId);

  /**
   * Finds all active models for a provider.
   *
   * @param providerId provider id
   * @return models
   */
  List<AiModelDefinition> findAllActiveByProviderId(String providerId);

  /**
   * Lists enabled and available system models.
   *
   * @return system models
   */
  List<AiModelDefinition> findAllAvailableSystemModels();

  /**
   * Lists enabled and available models visible to one tenant, including shared system models.
   *
   * @param tenantId tenant id
   * @return visible models
   */
  List<AiModelDefinition> findAllAvailableForTenant(String tenantId);

  /**
   * Searches selectable model labels for one dependency owner.
   */
  Page<AiDependencyOptionView> searchDependencyOptions(
      boolean tenantOwner,
      String ownerTenantId,
      String searchPattern,
      Pageable pageable
  );

  /**
   * Resolves visible current or historical model labels by model id.
   */
  List<AiDependencyResolutionView> resolveDependencyOptions(
      boolean tenantOwner,
      String ownerTenantId,
      Collection<String> resourceIds
  );
}
