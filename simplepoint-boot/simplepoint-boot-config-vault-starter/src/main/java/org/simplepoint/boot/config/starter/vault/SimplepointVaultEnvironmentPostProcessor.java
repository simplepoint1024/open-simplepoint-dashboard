package org.simplepoint.boot.config.starter.vault;

import java.io.IOException;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * Loads application-vault.properties and simplepoint-vault-{profile}.properties
 * before Spring Boot config data processing.
 */
public class SimplepointVaultEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  private static final Log logger =
      LogFactory.getLog(SimplepointVaultEnvironmentPostProcessor.class);

  private static final String DEFAULT_FILE = "application-vault.properties";
  private static final String PROFILE_FILE_PATTERN = "application-vault-%s.properties";

  private static final String SOURCE_NAME_PREFIX = "simplepointVaultProperties";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment,
                                     SpringApplication application) {

    MutablePropertySources sources = environment.getPropertySources();

    // 1. 加载默认配置 application-vault.properties
    loadIfExists(sources, DEFAULT_FILE, SOURCE_NAME_PREFIX + "-default");

    // 2. 加载 profile 配置 simplepoint-vault-{profile}.properties
    String property = environment.getProperty("spring.profiles.active");
    if (property == null || property.isBlank()) {
      return;
    }

    for (String profile : property.split(",")) {
      String fileName = String.format(PROFILE_FILE_PATTERN, profile);
      String sourceName = SOURCE_NAME_PREFIX + "-" + profile;
      loadIfExists(sources, fileName, sourceName);
    }
  }

  private void loadIfExists(MutablePropertySources sources, String fileName, String sourceName) {
    if (sources.contains(sourceName)) {
      return;
    }

    ClassPathResource resource = new ClassPathResource(fileName);
    if (!resource.exists()) {
      return;
    }

    try {
      ResourcePropertySource ps = new ResourcePropertySource(sourceName, resource);
      // profile-specific 覆盖默认配置，所以放在最前
      sources.addFirst(ps);
      logger.info("Loaded Vault config: " + fileName);
    } catch (IOException e) {
      logger.warn("Failed to load " + fileName, e);
    }
  }

  @Override
  public int getOrder() {
    // 必须在 ConfigDataEnvironmentPostProcessor 之前执行
    return ConfigDataEnvironmentPostProcessor.ORDER - 1;
  }
}
