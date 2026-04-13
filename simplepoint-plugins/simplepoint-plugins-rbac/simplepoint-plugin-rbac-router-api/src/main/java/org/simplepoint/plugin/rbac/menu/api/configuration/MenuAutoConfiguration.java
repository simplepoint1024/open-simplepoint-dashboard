package org.simplepoint.plugin.rbac.menu.api.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.security.MenuChildren;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Set;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ClassPathResource resource;

    private final String applicationName;

    /**
     * Constructor for MenuAutoConfiguration.
     * This constructor initializes the MenuService that will be used for menu management.
     *
     * @param menuService     the service used for managing menus
     * @param applicationName the application name
     * @param menuConfigPath  the classpath location of the menu configuration JSON
     */
    public MenuAutoConfiguration(
            @Autowired(required = false) MenuService menuService,
            @Value("${spring.application.name}")
            String applicationName,
            @Value("${simplepoint.menu.config-path:conf/simple-menu.json}")
            String menuConfigPath
    ) {
        this.menuService = menuService;
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
        if (!resource.exists()) {
            log.debug("Menu config not found: {}, skipping menu initialization", resource.getPath());
            return;
        }
        var menus = readConf();
        if (menus != null && !menus.isEmpty()) {
            log.info("Initializing menu data from configuration file: {}", resource.getPath());
            this.menuService.sync(applicationName, menus);
            log.info("Finished initializing menu data from configuration file: {}", resource.getPath());
        }
    }
}
