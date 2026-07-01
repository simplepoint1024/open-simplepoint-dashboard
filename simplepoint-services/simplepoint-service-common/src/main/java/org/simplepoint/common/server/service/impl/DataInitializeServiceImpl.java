package org.simplepoint.common.server.service.impl;

import java.time.LocalDateTime;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.common.server.repository.DataInitializeRepository;
import org.simplepoint.data.initialize.entity.DataInitialize;
import org.simplepoint.data.initialize.service.DataInitializeService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/**
 * Implementation of the DataInitializeService interface.
 */
@Slf4j
@Service
@RemoteProvider
public record DataInitializeServiceImpl(DataInitializeRepository repository) implements DataInitializeService {
  private static final int MAX_ERROR_LENGTH = 255;

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
      DataInitialize initialize = getOrCreate(serviceName, moduleName);
      initialize.setServiceName(serviceName);
      initialize.setModuleName(moduleName);
      initialize.setInitStatus(DataInitialize.STATUS_INIT);
      initialize.setError(null);
      initialize.setCreateTime(LocalDateTime.now());
      initialize.setDoneTime(null);
      repository.save(initialize);
      return true;
    } catch (Exception e) {
      log.error(e.getMessage(), e);
      return false;
    }
  }

  @Override
  public Boolean done(String serviceName, String moduleName) {
    DataInitialize initialize = getOrCreate(serviceName, moduleName);
    initialize.setServiceName(serviceName);
    initialize.setModuleName(moduleName);
    initialize.setInitStatus(DataInitialize.STATUS_DONE);
    initialize.setError(null);
    initialize.setDoneTime(LocalDateTime.now());
    repository.save(initialize);
    return true;
  }

  @Override
  public void fail(String serviceName, String moduleName, String error) {
    DataInitialize initialize = getOrCreate(serviceName, moduleName);
    initialize.setServiceName(serviceName);
    initialize.setModuleName(moduleName);
    initialize.setInitStatus(DataInitialize.STATUS_FAIL);
    initialize.setError(truncateError(error));
    repository.save(initialize);
  }

  @Override
  public Boolean isDone(String serviceName, String moduleName) {
    DataInitialize initialize = repository.findFirstByServiceNameAndModuleName(serviceName, moduleName);
    if (initialize == null) {
      return false;
    }
    return Objects.equals(initialize.getInitStatus(), DataInitialize.STATUS_DONE);
  }

  private DataInitialize getOrCreate(String serviceName, String moduleName) {
    DataInitialize initialize = repository.findFirstByServiceNameAndModuleName(serviceName, moduleName);
    return initialize == null ? new DataInitialize() : initialize;
  }

  private String truncateError(String error) {
    if (error == null || error.length() <= MAX_ERROR_LENGTH) {
      return error;
    }
    return error.substring(0, MAX_ERROR_LENGTH);
  }
}
