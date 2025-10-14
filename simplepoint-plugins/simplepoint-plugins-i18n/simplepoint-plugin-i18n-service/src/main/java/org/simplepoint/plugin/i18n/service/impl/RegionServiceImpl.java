package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.i18n.api.entity.Region;
import org.simplepoint.plugin.i18n.api.repository.RegionRepository;
import org.simplepoint.plugin.i18n.api.service.RegionService;
import org.springframework.stereotype.Service;

/**
 * RegionServiceImpl provides the implementation for RegionService.
 * It extends BaseServiceImpl to manage Region entities.
 * This service is used to interact with the persistence layer for region entities.
 */
@Service
public class RegionServiceImpl extends BaseServiceImpl<RegionRepository, Region, String> implements
    RegionService {
  /**
   * Constructs a BaseServiceImpl with the specified repository and access metadata sync service.
   *
   * @param repository the repository to be used for entity operations
   */
  public RegionServiceImpl(RegionRepository repository) {
    super(repository);
  }
}
