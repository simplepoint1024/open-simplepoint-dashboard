package org.simplepoint.data.initialize.service;

import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;

/**
 * Service interface for data initialization operations.
 */
@AmqpRemoteClient(to = "data.initialize")
public interface DataInitializeService {

  /**
   * Starts the initialization process for a specific module.
   *
   * @param serviceName the name of the service
   * @param moduleName  the name of the module to initialize
   * @return true if the operation was successful, false otherwise
   */
  Boolean start(String serviceName, String moduleName);

  /**
   * Marks the initialization process as completed.
   *
   * @param serviceName the name of the service
   * @param moduleName  the name of the module to mark as done
   * @return true if the operation was successful, false otherwise
   */
  Boolean done(String serviceName, String moduleName);

  /**
   * Marks the initialization process as failed.
   *
   * @param serviceName the name of the service
   * @param moduleName  the name of the module to mark as failed
   * @param error       the error message or reason for failure
   */
  void fail(String serviceName, String moduleName, String error);

  /**
   * Checks if a specific module has been initialized.
   *
   * @param serviceName the name of the service
   * @param moduleName  the name of the module to check
   * @return true if the module is initialized, false otherwise
   */
  Boolean isDone(String serviceName, String moduleName);
}
