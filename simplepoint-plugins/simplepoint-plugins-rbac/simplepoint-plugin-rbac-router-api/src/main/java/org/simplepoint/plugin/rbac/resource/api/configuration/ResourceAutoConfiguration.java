package org.simplepoint.plugin.rbac.resource.api.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.service.ResourceService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.ClassPathResource;

/**
 * Auto-registers module resources from classpath configuration.
 */
@Slf4j
@AutoConfiguration
public class ResourceAutoConfiguration implements InitializingBean {

  private final ObjectProvider<ResourceService> resourceServiceProvider;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ClassPathResource resource;
  private final String applicationName;

  /**
   * Creates resource auto-configuration.
   */
  public ResourceAutoConfiguration(
      ObjectProvider<ResourceService> resourceServiceProvider,
      @Value("${spring.application.name}")
      String applicationName,
      @Value("${simplepoint.resource.config-path:conf/simple-resources.json}")
      String resourceConfigPath
  ) {
    this.resourceServiceProvider = resourceServiceProvider;
    this.applicationName = applicationName;
    this.resource = new ClassPathResource(resourceConfigPath);
  }

  private Set<ResourceDeclaration> readConf() throws IOException {
    try (var inputStream = resource.getInputStream()) {
      return objectMapper.readValue(inputStream, new TypeReference<>() {
      });
    }
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    ResourceService resourceService = resourceServiceProvider.getIfAvailable();
    if (resourceService == null) {
      log.debug("ResourceService bean not available, skipping resource initialization");
      return;
    }
    if (!resource.exists()) {
      log.debug("Resource config not found: {}, skipping resource initialization", resource.getPath());
      return;
    }
    Set<ResourceDeclaration> resources = readConf();
    if (resources != null && !resources.isEmpty()) {
      log.info("Initializing resources from configuration file: {}", resource.getPath());
      resourceService.sync(applicationName, resources);
      log.info("Finished initializing resources from configuration file: {}", resource.getPath());
    }
  }
}
