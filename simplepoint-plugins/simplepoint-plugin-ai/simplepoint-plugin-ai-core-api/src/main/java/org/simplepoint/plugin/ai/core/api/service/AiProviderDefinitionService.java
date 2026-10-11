package org.simplepoint.plugin.ai.core.api.service;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import org.simplepoint.api.base.BaseService;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.vo.AiProviderOption;

/**
 * Service contract for provider configuration management.
 */
public interface AiProviderDefinitionService extends BaseService<AiProviderDefinition, String> {

  /**
   * Finds an active provider by id.
   *
   * @param id provider id
   * @return provider
   */
  Optional<AiProviderDefinition> findActiveById(String id);

  /** Resolves at most 100 selected IDs inside the current management scope, including disabled rows. */
  List<AiProviderOption> selectedOptions(Collection<String> ids);

  /**
   * Finds an active provider by business code.
   *
   * @param code provider code
   * @return provider
   */
  Optional<AiProviderDefinition> findActiveByCode(String code);

  /**
   * Lists providers enabled for automatic model synchronization.
   *
   * @return providers
   */
  List<AiProviderDefinition> listAutoSyncProviders();
}
