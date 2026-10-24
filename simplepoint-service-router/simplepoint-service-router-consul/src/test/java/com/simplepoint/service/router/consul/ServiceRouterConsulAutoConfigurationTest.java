package com.simplepoint.service.router.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.simplepoint.service.router.metadata.RoutedServiceMetadataResolver;
import com.simplepoint.service.router.registry.CapabilityRegistry;
import com.simplepoint.service.router.registry.LocalRoutedService;
import com.simplepoint.service.router.registry.LocalServiceRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.consul.serviceregistry.ConsulRegistration;

class ServiceRouterConsulAutoConfigurationTest {

  @Test
  void disabledExportsRemoveStaleAdvertisementsButPreserveUnrelatedMetadata() {
    var local = mock(LocalServiceRegistry.class);
    var registry = new CapabilityRegistry(local, false);
    var metadata = customize(registry);

    assertEquals(Map.of("zone", "test"), metadata);
  }

  @Test
  void absentRegistryCannotLeaveConfiguredCapabilityAdvertisements() {
    assertEquals(Map.of("zone", "test"), customize(null));
  }

  @Test
  void enabledExportsReplaceOldMappingsWithTheActualHostAndContract() {
    var local = mock(LocalServiceRegistry.class);
    var contract = RoutedServiceMetadataResolver.resolve(Identity.class).orElseThrow();
    when(local.all()).thenReturn(List.of(new LocalRoutedService(contract, () -> null, Identity.class)));

    assertEquals(Map.of("zone", "test",
        ServiceRouterDiscoveryMetadata.CAPABILITIES, "fixture.identity:2",
        ServiceRouterDiscoveryMetadata.MAPPINGS, "fixture.identity:2=common"),
        customize(new CapabilityRegistry(local)));
  }

  private static Map<String, String> customize(final CapabilityRegistry registry) {
    var beans = new StaticListableBeanFactory();
    if (registry != null) {
      beans.addBean("capabilities", registry);
    }
    var metadata = new HashMap<>(Map.of(
        "zone", "test",
        ServiceRouterDiscoveryMetadata.CAPABILITIES, "stale:1",
        ServiceRouterDiscoveryMetadata.MAPPINGS, "stale:1=wrong-host"));
    var registration = mock(ConsulRegistration.class);
    when(registration.getMetadata()).thenReturn(metadata);
    new ServiceRouterConsulAutoConfiguration()
        .serviceRouterConsulRegistrationCustomizer("common",
            beans.getBeanProvider(CapabilityRegistry.class))
        .customize(registration);
    return metadata;
  }

  @RemoteContract(name = "fixture.identity", version = "2")
  interface Identity {}
}
