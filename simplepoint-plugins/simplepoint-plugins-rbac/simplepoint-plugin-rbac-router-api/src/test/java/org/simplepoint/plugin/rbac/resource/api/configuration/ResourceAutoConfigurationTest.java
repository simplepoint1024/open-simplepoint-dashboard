package org.simplepoint.plugin.rbac.resource.api.configuration;

import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.simplepoint.security.service.ResourceService;
import org.springframework.beans.factory.ObjectProvider;

class ResourceAutoConfigurationTest {

  @Test
  @SuppressWarnings("unchecked")
  void initializesResourcesWhenResourceServiceBecomesAvailable() throws Exception {
    ResourceService resourceService = mock(ResourceService.class);
    ObjectProvider<ResourceService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(resourceService);

    ResourceAutoConfiguration configuration = new ResourceAutoConfiguration(provider, "dna", "conf/test-resources.json");

    configuration.afterPropertiesSet();

    verify(resourceService).sync(eq("dna"), anySet());
  }

  @Test
  @SuppressWarnings("unchecked")
  void skipsResourceInitializationWhenResourceServiceIsUnavailable() throws Exception {
    ObjectProvider<ResourceService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);

    ResourceAutoConfiguration configuration = new ResourceAutoConfiguration(provider, "dna", "conf/test-resources.json");

    configuration.afterPropertiesSet();

    verify(provider).getIfAvailable();
  }

  @Test
  @SuppressWarnings("unchecked")
  void skipsResourceInitializationWhenConfigIsMissing() throws Exception {
    ResourceService resourceService = mock(ResourceService.class);
    ObjectProvider<ResourceService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(resourceService);

    ResourceAutoConfiguration configuration = new ResourceAutoConfiguration(provider, "dna", "conf/missing-resources.json");

    configuration.afterPropertiesSet();

    verify(resourceService, never()).sync(eq("dna"), anySet());
  }
}
