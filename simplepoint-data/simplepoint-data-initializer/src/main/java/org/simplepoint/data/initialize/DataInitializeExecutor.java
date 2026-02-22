package org.simplepoint.data.initialize;

import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.api.data.DataInitRegister;
import org.simplepoint.api.data.InitTask;
import org.simplepoint.data.initialize.properties.DataInitializeProperties;
import org.simplepoint.data.initialize.properties.InitializerSettings;
import org.simplepoint.data.initialize.service.DataInitializeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Manager class for data initialization.
 */
@Slf4j
public final class DataInitializeExecutor implements ApplicationRunner {
  private final String serviceName;

  private final DataInitializeService initializeService;

  private final Set<DataInitRegister> registers;

  private final DataInitializeProperties properties;

  /**
   * Constructor for DataInitializeManager.
   *
   * @param initializeService the DataInitializeService to be used
   */
  public DataInitializeExecutor(
      @Value("${spring.application.name}")
      String serviceName,
      DataInitializeService initializeService,
      Set<DataInitRegister> registers,
      DataInitializeProperties properties
  ) {
    this.serviceName = serviceName;
    this.initializeService = initializeService;
    this.registers = registers;
    this.properties = properties;
  }

  /**
   * Initializes data for a given module using the provided initializer.
   *
   * @param moduleName  the name of the module
   * @param initializer the initializer to run
   * @return true if initialization was successful, false otherwise
   */
  public boolean execute(String moduleName, InitTask.Initializer initializer) {
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
  public boolean execute(String serviceName, String moduleName, InitTask.Initializer initializer) {
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


  @Override
  public void run(ApplicationArguments args) {
    if (properties.isEnabled()) {
      Map<String, InitializerSettings> module = properties.getModule();
      if (module != null) {
        this.registers.forEach(register -> {
          InitTask task = register.register();
          InitializerSettings settings = module.get(task.moduleName());
          if (settings != null && settings.enabled()) {
            log.info("Register data initialization task for module: {}", task.moduleName());
            this.execute(task.moduleName(), task.task());
            log.info("Data initialization completed for module: {}", task.moduleName());
          } else {
            log.info("Data initialization task for module: {} is disabled, skipping execution", task.moduleName());
          }
        });
      }
    }
  }
}
