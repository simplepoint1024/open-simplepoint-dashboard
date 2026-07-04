package org.simplepoint.plugin.rbac.resource.api.configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.service.ResourceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Auto-registers module resources from classpath configuration.
 */
@Slf4j
@AutoConfiguration
public class ResourceAutoConfiguration implements ApplicationRunner {

  private static final String MODULE_RESOURCE_PATTERN = "classpath*:META-INF/simplepoint/resources/*.json";

  private final ObjectProvider<ResourceService> resourceServiceProvider;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
  private final ClassPathResource legacyResource;
  private final String legacyResourcePath;
  private final String applicationName;

  /**
   * Creates resource auto-configuration.
   */
  public ResourceAutoConfiguration(
      ObjectProvider<ResourceService> resourceServiceProvider,
      @Value("${spring.application.name}")
      String applicationName,
      @Value("${simplepoint.resource.config-path:}")
      String resourceConfigPath
  ) {
    this.resourceServiceProvider = resourceServiceProvider;
    this.applicationName = applicationName;
    this.legacyResourcePath = resourceConfigPath;
    this.legacyResource = hasText(resourceConfigPath) ? new ClassPathResource(resourceConfigPath) : null;
  }

  private Set<ResourceDeclaration> readLegacyResources() throws IOException {
    try (var inputStream = legacyResource.getInputStream()) {
      return objectMapper.readValue(inputStream, new TypeReference<LinkedHashSet<ResourceDeclaration>>() {
      });
    }
  }

  private List<ResourceModuleConfiguration> readModuleConfigurations() throws IOException {
    List<ResourceModuleConfiguration> configurations = new ArrayList<>();
    for (Resource resource : resolver.getResources(MODULE_RESOURCE_PATTERN)) {
      if (!resource.exists() || resource.getFilename() == null || !resource.getFilename().endsWith(".json")) {
        continue;
      }
      try (var inputStream = resource.getInputStream()) {
        JsonNode node = objectMapper.readTree(inputStream);
        ResourceModuleConfiguration configuration;
        if (node.isArray()) {
          Set<ResourceDeclaration> resources = objectMapper.convertValue(
              node,
              new TypeReference<LinkedHashSet<ResourceDeclaration>>() {
              }
          );
          configuration = new ResourceModuleConfiguration();
          configuration.setResources(resources);
        } else {
          configuration = objectMapper.convertValue(node, ResourceModuleConfiguration.class);
        }
        configuration.setSource(resource.getDescription());
        configurations.add(configuration);
      }
    }
    return configurations;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    ResourceService resourceService = resourceServiceProvider.getIfAvailable();
    if (resourceService == null) {
      log.debug("ResourceService bean not available, skipping resource initialization");
      return;
    }
    List<ResourceModuleConfiguration> configurations = readModuleConfigurations();
    for (ResourceModuleConfiguration configuration : configurations) {
      Set<ResourceDeclaration> resources = configuration.getResources();
      if (resources == null || resources.isEmpty()) {
        log.debug("Resource module configuration is empty: {}", configuration.getSource());
        continue;
      }
      String owner = hasText(configuration.getModule()) ? configuration.getModule() : applicationName;
      log.info("Initializing resources from module configuration: {}", configuration.getSource());
      resourceService.sync(owner, resources);
      log.info("Finished initializing resources from module configuration: {}", configuration.getSource());
    }

    if (legacyResource != null && legacyResource.exists()) {
      Set<ResourceDeclaration> resources = readLegacyResources();
      if (resources != null && !resources.isEmpty()) {
        log.info("Initializing resources from legacy configuration file: {}", legacyResource.getPath());
        resourceService.sync(applicationName, resources);
        log.info("Finished initializing resources from legacy configuration file: {}", legacyResource.getPath());
      }
    } else if (configurations.isEmpty()) {
      log.debug("Resource config not found: {}, skipping resource initialization", legacyResourcePath);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ResourceModuleConfiguration {
    private String module;
    private Set<ResourceDeclaration> resources;
    private String source;

    public String getModule() {
      return module;
    }

    public void setModule(String module) {
      this.module = module;
    }

    public Set<ResourceDeclaration> getResources() {
      return resources;
    }

    public void setResources(Set<ResourceDeclaration> resources) {
      this.resources = resources;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }
  }
}
