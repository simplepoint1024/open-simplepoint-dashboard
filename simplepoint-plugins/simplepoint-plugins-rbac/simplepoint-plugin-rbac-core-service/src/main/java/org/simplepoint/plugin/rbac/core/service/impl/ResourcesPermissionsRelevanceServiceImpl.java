package org.simplepoint.plugin.rbac.core.service.impl;

import java.util.Collection;
import org.simplepoint.plugin.rbac.core.api.repository.ResourcesRelevanceRepository;
import org.simplepoint.plugin.rbac.core.api.service.ResourcesPermissionsRelevanceService;
import org.simplepoint.security.entity.ResourcesPermissionsRelevance;
import org.springframework.stereotype.Service;

/**
 * Implementation of the ResourcesRelevanceService interface for managing Resource entities.
 *
 * @since v0.0.2
 */
@Service
public class ResourcesPermissionsRelevanceServiceImpl implements ResourcesPermissionsRelevanceService {
  private final ResourcesRelevanceRepository resourcesRelevanceRepository;

  /**
   * Constructs a ResourcesServiceImpl with the specified ResourcesRelevanceRepository.
   *
   * @param resourcesRelevanceRepository the repository for managing resource relevance
   */
  public ResourcesPermissionsRelevanceServiceImpl(ResourcesRelevanceRepository resourcesRelevanceRepository) {
    this.resourcesRelevanceRepository = resourcesRelevanceRepository;
  }

  @Override
  public void removeAllByAuthority(String authority) {
    resourcesRelevanceRepository.removeAllByAuthority(authority);
  }

  @Override
  public void removeAllByAuthorities(Collection<String> authorities) {
    resourcesRelevanceRepository.removeAllByAuthorities(authorities);
  }

  @Override
  public void authorize(Collection<ResourcesPermissionsRelevance> collection) {
    resourcesRelevanceRepository.authorize(collection);
  }

  @Override
  public void unauthorize(String authority, Collection<String> resourceAuthorities) {
    resourcesRelevanceRepository.unauthorize(authority, resourceAuthorities);
  }

  @Override
  public Collection<String> authorized(String resourceAuthority) {
    return resourcesRelevanceRepository.authorized(resourceAuthority);
  }

  @Override
  public Collection<String> loadAllResourceAuthorities(Collection<String> resourceAuthorities) {
    return resourcesRelevanceRepository.loadAllResourceAuthorities(resourceAuthorities);
  }
}
