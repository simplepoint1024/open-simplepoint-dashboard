package org.simplepoint.common.server.service.impl;

import java.time.LocalDateTime;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.common.server.repository.DataInitializeRepository;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;
import org.simplepoint.data.initialize.entity.DataInitialize;
import org.simplepoint.data.initialize.service.DataInitializeService;

/**
 * Implementation of the DataInitializeService interface.
 */
@Slf4j
@AmqpRemoteService
public record DataInitializeServiceImpl(DataInitializeRepository repository) implements DataInitializeService {
  /**
   * Constructor for DataInitializeServiceImpl.
   *
   * @param repository the DataInitializeRepository to be used
   */
  public DataInitializeServiceImpl {
  }

  @Override
  public Boolean start(String serviceName, String moduleName) {
    try {
      DataInitialize initialize = new DataInitialize();
      initialize.setServiceName(serviceName);
      initialize.setModuleName(moduleName);
      initialize.setInitStatus(DataInitialize.STATUS_INIT);
      initialize.setCreateTime(LocalDateTime.now());
      repository.save(initialize);
      return true;
    } catch (Exception e) {
      log.error(e.getMessage(), e);
      return false;
    }
  }

  @Override
  public Boolean done(String serviceName, String moduleName) {
    DataInitialize initialize = new DataInitialize();
    initialize.setServiceName(serviceName);
    initialize.setModuleName(moduleName);
    initialize.setInitStatus(DataInitialize.STATUS_DONE);
    initialize.setDoneTime(LocalDateTime.now());
    repository.save(initialize);
    return true;
  }

  @Override
  public void fail(String serviceName, String moduleName, String error) {
  }

  @Override
  public Boolean isDone(String serviceName, String moduleName) {
    DataInitialize initialize = repository.findFirstByServiceNameAndModuleName(serviceName, moduleName);
    if (initialize == null) {
      return false;
    }
    return Objects.equals(initialize.getInitStatus(), DataInitialize.STATUS_DONE);
  }
}
