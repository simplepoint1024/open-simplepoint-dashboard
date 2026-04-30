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
    String property = environment.getProperty("spring.profiles.active");

    // 2. 加载默认配置 application-consul.properties
    loadIfExists(sources, DEFAULT_FILE, SOURCE_NAME_PREFIX + "-default");

    // 3. 加载 profile 配置 application-consul-{profile}.properties
    if (property == null || property.isBlank()) {
      return;
    }

    for (String profile : property.split(",")) {
      String fileName = String.format(PROFILE_FILE_PATTERN, profile.trim());
      String sourceName = SOURCE_NAME_PREFIX + "-" + profile.trim();
      loadIfExists(sources, fileName, sourceName);
    }

    // 4. 将 spring.profiles.active 的原始值（来自环境变量）添加为最高优先级，
    //    确保 application-consul.properties 中的默认值不会覆盖它。
    //    这样 Spring Boot 激活 profile 时会使用完整的 profiles（如 dev,swarm）。
    sources.addFirst(new MapPropertySource(
        SOURCE_NAME_PREFIX + "-profileOverride",
        Map.of("spring.profiles.active", property)
    ));
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
