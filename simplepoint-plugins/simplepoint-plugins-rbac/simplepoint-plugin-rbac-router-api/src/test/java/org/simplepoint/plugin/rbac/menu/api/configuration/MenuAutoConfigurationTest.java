package org.simplepoint.plugin.rbac.menu.api.configuration;

import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.simplepoint.security.service.MenuService;
import org.springframework.beans.factory.ObjectProvider;

class MenuAutoConfigurationTest {

  @Test
  @SuppressWarnings("unchecked")
  void initializesMenuWhenMenuServiceBecomesAvailable() throws Exception {
    MenuService menuService = mock(MenuService.class);
    ObjectProvider<MenuService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(menuService);

    MenuAutoConfiguration configuration = new MenuAutoConfiguration(provider, "dna", "conf/test-menu.json");

    configuration.afterPropertiesSet();

    verify(menuService).sync(eq("dna"), anySet());
  }

  @Test
  @SuppressWarnings("unchecked")
  void skipsMenuInitializationWhenMenuServiceIsUnavailable() throws Exception {
    ObjectProvider<MenuService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);

    MenuAutoConfiguration configuration = new MenuAutoConfiguration(provider, "dna", "conf/test-menu.json");

    configuration.afterPropertiesSet();

    verify(provider).getIfAvailable();
  }

  @Test
  @SuppressWarnings("unchecked")
  void skipsMenuInitializationWhenConfigIsMissing() throws Exception {
    MenuService menuService = mock(MenuService.class);
    ObjectProvider<MenuService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(menuService);

    MenuAutoConfiguration configuration = new MenuAutoConfiguration(provider, "dna", "conf/missing-menu.json");

    configuration.afterPropertiesSet();

    verify(menuService, never()).sync(eq("dna"), anySet());
  }
}
