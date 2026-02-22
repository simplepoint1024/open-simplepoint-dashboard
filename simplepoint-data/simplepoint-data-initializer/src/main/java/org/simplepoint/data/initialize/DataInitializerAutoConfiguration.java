package org.simplepoint.data.initialize;

import java.util.Set;
import org.simplepoint.api.data.DataInitRegister;
import org.simplepoint.data.initialize.properties.DataInitializeProperties;
import org.simplepoint.data.initialize.service.DataInitializeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration class for data initialization.
 *
 * <p>This class is responsible for enabling the configuration properties for data initialization and
 * setting up any necessary beans or components related to data initialization. It is annotated with
 *
 */
@AutoConfiguration
@EnableConfigurationProperties(DataInitializeProperties.class)
public class DataInitializerAutoConfiguration {

  /**
   * Creates a DataInitializeExecutor bean that will be responsible for executing data initialization tasks.
   *
   * <p>This method takes in the service name, a DataInitializeService instance, a set of DataInitRegister instances,
   * and DataInitializeProperties. It returns a new instance of DataInitializeExecutor initialized with these parameters.</p>
   *
   * @param serviceName       the name of the service, injected from the application properties
   * @param initializeService the service responsible for performing data initialization
   * @param registers         a set of DataInitRegister instances that define the data initialization tasks to be executed
   * @param properties        the properties related to data initialization configuration
   * @return a DataInitializeExecutor bean
   */
  @Bean
  public DataInitializeExecutor dataInitializeExecutor(
      @Value("${spring.application.name}")
      String serviceName,
      DataInitializeService initializeService,
      Set<DataInitRegister> registers,
      DataInitializeProperties properties
  ) {
    return new DataInitializeExecutor(
        serviceName,
        initializeService,
        registers,
        properties
    );
  }
}
