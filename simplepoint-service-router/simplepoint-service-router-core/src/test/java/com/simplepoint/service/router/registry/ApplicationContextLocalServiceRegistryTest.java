package com.simplepoint.service.router.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simplepoint.service.router.exception.AmbiguousServiceProviderException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;

class ApplicationContextLocalServiceRegistryTest {

  @Test
  void rejectsDuplicateProvidersRegardlessOfRegistrationOrder() {
    for (List<String> order : List.of(List.of("first", "second"), List.of("second", "first"))) {
      try (var context = new GenericApplicationContext()) {
        for (String name : order) {
          context.registerBeanDefinition(name, new RootBeanDefinition(Provider.class));
        }
        context.refresh();
        var registry = new ApplicationContextLocalServiceRegistry(context);

        assertThatThrownBy(registry::afterPropertiesSet)
            .isInstanceOf(AmbiguousServiceProviderException.class)
            .hasMessage("Ambiguous providers for sample.contract:1: [first, second]");
      }
    }
  }

  @Test
  void allowsDifferentVersionsAndDoesNotExposeMutableRegistry() {
    try (var context = new GenericApplicationContext()) {
      context.registerBeanDefinition("v1", new RootBeanDefinition(Provider.class));
      context.registerBeanDefinition("v2", new RootBeanDefinition(VersionTwoProvider.class));
      context.refresh();
      var registry = new ApplicationContextLocalServiceRegistry(context);
      registry.afterPropertiesSet();

      assertThat(registry.find("sample.contract", "1")).isPresent();
      assertThat(registry.find("sample.contract", "2")).isPresent();
      assertThatThrownBy(() -> registry.all().clear())
          .isInstanceOf(UnsupportedOperationException.class);
      assertThat(registry.all()).hasSize(2);
    }
  }

  @Test
  void repeatedInheritedInterfaceIsOneCapability() {
    try (var context = new GenericApplicationContext()) {
      context.registerBeanDefinition("provider", new RootBeanDefinition(InheritedProvider.class));
      context.refresh();
      var registry = new ApplicationContextLocalServiceRegistry(context);
      registry.afterPropertiesSet();

      assertThat(registry.all()).hasSize(1);
      assertThat(registry.find("sample.contract", "1").orElseThrow().bean())
          .isSameAs(context.getBean("provider"));
    }
  }

  @RemoteContract(name = "sample.contract")
  interface Contract {}

  @RemoteContract(name = "sample.contract", version = "2")
  interface VersionTwoContract {}

  interface Left extends Contract {}

  interface Right extends Contract {}

  static class Provider implements Contract {}

  static class VersionTwoProvider implements VersionTwoContract {}

  static class InheritedProvider implements Left, Right {}
}
