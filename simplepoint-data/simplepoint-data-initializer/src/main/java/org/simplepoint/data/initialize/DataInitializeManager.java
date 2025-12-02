package org.simplepoint.data.initialize;

import static org.simplepoint.api.environment.EnvironmentConfiguration.SIMPLEPOINT_NAME;

import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.properties.CoreProperties;
import org.simplepoint.data.initialize.service.DataInitializeService;
import org.springframework.stereotype.Component;

/**
 * Manager class for data initialization.
 */
@Slf4j
@Component
public final class DataInitializeManager {
  private final String serviceName = CoreProperties.getProperty(SIMPLEPOINT_NAME);

  private final DataInitializeService initializeService;

  /**
   * Constructor for DataInitializeManager.
   *
   * @param initializeService the DataInitializeService to be used
   */
  public DataInitializeManager(DataInitializeService initializeService) {
    this.initializeService = initializeService;
  }

  /**
   * Initializes data for a given module using the provided initializer.
   *
   * @param moduleName  the name of the module
   * @param initializer the initializer to run
   * @return true if initialization was successful, false otherwise
   */
  public boolean execute(String moduleName, Initializer initializer) {
    return execute(serviceName, moduleName, initializer);
  }

  /**
   * Initializes data for a given service and module using the provided initializer.
   *
   * @param serviceName the name of the service
   * @param moduleName  the name of the module
   * @param initializer the initializer to run
   * @return true if initialization was successful, false otherwise
   */
  public boolean execute(String serviceName, String moduleName, Initializer initializer) {
    try {
      // 检查是否已经初始化完成
      boolean done = isInitialized(moduleName);
      // 如果未完成，则开始初始化
      if (!done) {
        // 标记为开始
        Boolean started = initializeService.start(serviceName, moduleName);
        if (started != null && started) {
          initializer.run();
          initializeService.done(serviceName, moduleName);
        }
      }
    } catch (Exception e) {
      // 记录初始化失败日志并标记为失败
      log.warn("Initialization failed for service: {}, module: {}. Error: {}", serviceName, moduleName, e.getMessage(), e);
      initializeService.fail(serviceName, moduleName, e.getLocalizedMessage());
    }
    return false;
  }

  /**
   * Check if a specific module has been initialized.
   *
   * @param moduleName the name of the module to check
   * @return true if the module is initialized, false otherwise
   */
  private boolean isInitialized(String moduleName) {
    try {
      Boolean done = initializeService.isDone(serviceName, moduleName);
      if (done == null) {
        log.warn("Remote service is offline, or returned an empty result for module: {}", moduleName);
        return false;
      }
      return done;
    } catch (Exception e) {
      log.info("check initialized failed for module: {}", moduleName, e);
      return false;
    }
  }

  /**
   * Functional interface for data initializers.
   */
  public interface Initializer {
    /**
     * Run the initialization logic.
     *
     * @throws Exception if an error occurs during initialization
     */
    void run() throws Exception;
  }
}
