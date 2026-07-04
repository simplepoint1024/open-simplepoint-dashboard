package org.simplepoint.plugin.rbac.menu.api.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.io.ClassPathResource;

/**
 * Auto-configuration class for the Menu module in the RBAC plugin.
 * This class is used to enable automatic configuration of beans related to menu management.
 * It is part of the Spring Boot auto-configuration mechanism.
 *
 * @since 1.0
 */
@Slf4j
@AutoConfiguration
public class MenuAutoConfiguration implements InitializingBean {
  private final ObjectProvider<MenuService> menuServiceProvider;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final ClassPathResource resource;

  private final String applicationName;

  /**
     * Constructor for MenuAutoConfiguration.
     * This constructor initializes the MenuService that will be used for menu management.
     *
     * @param menuServiceProvider provider for the service used for managing menus
     * @param applicationName the application name
     * @param menuConfigPath  the classpath location of the menu configuration JSON
     */
  public MenuAutoConfiguration(
      ObjectProvider<MenuService> menuServiceProvider,
      @Value("${spring.application.name}")
      String applicationName,
      @Value("${simplepoint.menu.config-path:conf/simple-menu.json}")
      String menuConfigPath
  ) {
    this.menuServiceProvider = menuServiceProvider;
    this.applicationName = applicationName;
    this.resource = new ClassPathResource(menuConfigPath);
  }

  private Set<MenuChildren> readConf() throws IOException {
    try (var inputStream = resource.getInputStream()) {
      return objectMapper.readValue(inputStream, new TypeReference<>() {
      });
    }
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    MenuService availableMenuService = menuServiceProvider.getIfAvailable();
    if (availableMenuService == null) {
      log.debug("MenuService bean not available, skipping menu initialization");
      return;
    }
    if (!resource.exists()) {
      log.debug("Menu config not found: {}, skipping menu initialization", resource.getPath());
      return;
    }
    var menus = readConf();
    if (menus != null && !menus.isEmpty()) {
      log.info("Initializing menu data from configuration file: {}", resource.getPath());
      availableMenuService.sync(applicationName, menus);
      log.info("Finished initializing menu data from configuration file: {}", resource.getPath());
    }
  }
}
