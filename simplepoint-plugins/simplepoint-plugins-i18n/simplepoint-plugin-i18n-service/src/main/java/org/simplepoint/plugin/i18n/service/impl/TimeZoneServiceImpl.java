package org.simplepoint.plugin.i18n.service.impl;

import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.i18n.api.entity.TimeZone;
import org.simplepoint.plugin.i18n.api.repository.TimeZoneRepository;
import org.simplepoint.plugin.i18n.api.service.TimeZoneService;
import org.springframework.stereotype.Service;

/**
 * TimeZoneServiceImpl provides the implementation for TimeZoneService.
 * It extends BaseServiceImpl to manage TimeZone entities.
 * This service is used to interact with the persistence layer for time zone entities.
 */
@Service
public class TimeZoneServiceImpl extends BaseServiceImpl<TimeZoneRepository, TimeZone, String>
    implements
    TimeZoneService {

  /**
   * Constructs a BaseServiceImpl with the specified repository and access metadata sync service.
   *
   * @param repository the repository to be used for entity operations
   */
  public TimeZoneServiceImpl(
      TimeZoneRepository repository
  ) {
    super(repository);
  }
}
