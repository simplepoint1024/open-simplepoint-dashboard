package org.simplepoint.boot.config.starter.consul;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * Loads application-consul.properties and simplepoint-consul-{profile}.properties
 * before Spring Boot config data processing.
 */
public class SimplepointConsulEnvironmentPostProcessor
    implements EnvironmentPostProcessor, Ordered {

  private static final Log logger =
      LogFactory.getLog(SimplepointConsulEnvironmentPostProcessor.class);

  private static final String DEFAULT_FILE = "application-consul.properties";
  private static final String PROFILE_FILE_PATTERN = "application-consul-%s.properties";

  private static final String SOURCE_NAME_PREFIX = "simplepointConsulProperties";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment,
                                     SpringApplication application) {

    MutablePropertySources sources = environment.getPropertySources();

    // 1. 先读取 profiles (在加载默认文件之前，避免 application-consul.properties 中的
    //    spring.profiles.active 通过 addFirst 覆盖环境变量传入的 profiles)
    String originalProfiles = environment.getProperty("spring.profiles.active");

    // 2. 加载默认配置 application-consul.properties
    loadDefaultIfExists(sources);

    // 3. 加载 profile 配置 application-consul-{profile}.properties
    String activeProfiles = originalProfiles;
    if (activeProfiles == null || activeProfiles.isBlank()) {
      activeProfiles = environment.getProperty("spring.profiles.active");
    }
    if (activeProfiles == null || activeProfiles.isBlank()) {
      return;
    }

    // Spring applies later active profiles with higher precedence. Load the profile
    // property sources in reverse so "dev,swarm" lets swarm override dev.
    List<String> profiles = List.of(activeProfiles.split(","));
    for (int index = profiles.size() - 1; index >= 0; index--) {
      String profile = profiles.get(index).trim();
      if (profile.isEmpty()) {
        continue;
      }
      String fileName = String.format(PROFILE_FILE_PATTERN, profile);
      String sourceName = SOURCE_NAME_PREFIX + "-" + profile;
      loadProfileIfExists(sources, fileName, sourceName);
    }

    // 4. 将 spring.profiles.active 的原始值（来自环境变量）添加为最高优先级，
    //    确保 application-consul.properties 中的默认值不会覆盖它。
    //    这样 Spring Boot 激活 profile 时会使用完整的 profiles（如 dev,swarm）。
    if (originalProfiles != null && !originalProfiles.isBlank()) {
      sources.addFirst(new MapPropertySource(
          SOURCE_NAME_PREFIX + "-profileOverride",
          Map.of("spring.profiles.active", originalProfiles)
      ));
    }
  }

  private void loadDefaultIfExists(MutablePropertySources sources) {
    loadIfExists(sources, DEFAULT_FILE, SOURCE_NAME_PREFIX + "-default", null);
  }

  private void loadProfileIfExists(MutablePropertySources sources, String fileName, String sourceName) {
    loadIfExists(sources, fileName, sourceName, SOURCE_NAME_PREFIX + "-default");
  }

  private void loadIfExists(
      MutablePropertySources sources,
      String fileName,
      String sourceName,
      String beforeSourceName
  ) {
    if (sources.contains(sourceName)) {
      return;
    }

    ClassPathResource resource = new ClassPathResource(fileName);
    if (!resource.exists()) {
      return;
    }

    try {
      ResourcePropertySource ps = new ResourcePropertySource(sourceName, resource);
      if (beforeSourceName != null && sources.contains(beforeSourceName)) {
        sources.addBefore(beforeSourceName, ps);
      } else {
        sources.addLast(ps);
      }
      logger.info("Loaded Consul config: " + fileName);
    } catch (IOException e) {
      logger.warn("Failed to load " + fileName, e);
    }
  }

  @Override
  public int getOrder() {
    // 必须在 ConfigDataEnvironmentPostProcessor 之前执行
    return ConfigDataEnvironmentPostProcessor.ORDER - 2;
  }
}
