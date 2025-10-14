package org.simplepoint.plugin.rbac.menu.api.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
@ConditionalOnBean(MenuService.class)
public class MenuAutoConfiguration implements InitializingBean {
  private final MenuService menuService;

  private final ObjectMapper objectMapper;

  private final ClassPathResource resource = new ClassPathResource("conf/simple-menu.json");

  /**
   * Constructor for MenuAutoConfiguration.
   * This constructor initializes the MenuService that will be used for menu management.
   *
   * @param menuService the service used for managing menus
   */
  public MenuAutoConfiguration(
      @Autowired(required = false) MenuService menuService,
      ObjectMapper objectMapper
  ) {
    this.menuService = menuService;
    this.objectMapper = objectMapper;
  }

  private Set<MenuChildren> readConf() throws IOException {
    try (var inputStream = resource.getInputStream()) {
      return objectMapper.readValue(inputStream, new TypeReference<>() {
      });
    }
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    var menus = readConf();
    if (menus != null && !menus.isEmpty()) {
      log.info("Initializing menu data from configuration file: {}", resource.getPath());
      this.menuService.sync(menus);
      log.info("Finished initializing menu data from configuration file: {}", resource.getPath());
    }
  }
}
