package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.i18n.api.entity.Countries;
import org.simplepoint.plugin.i18n.api.repository.CountriesRepository;
import org.simplepoint.plugin.i18n.api.service.CountriesService;
import org.springframework.stereotype.Service;

/**
 * CountriesServiceImpl provides the implementation for CountriesService.
 * It extends BaseServiceImpl to manage Countries entities.
 * This service is used to interact with the persistence layer for country entities.
 */
@Service
public class CountriesServiceImpl extends BaseServiceImpl<CountriesRepository, Countries, String>
    implements CountriesService {

  /**
   * Constructs a BaseServiceImpl with the specified repository and access metadata sync service.
   *
   * @param repository the repository to be used for entity operations
   */
  public CountriesServiceImpl(CountriesRepository repository) {
    super(repository);
  }
}
