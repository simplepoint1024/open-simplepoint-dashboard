package org.simplepoint.platform.composition.spring;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.composition.fixture.api.CallerIdentity;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ModuleClientDefinitionTest {
  @Test
  void singletonReplacementCannotCacheTheFirstCallersIdentityForEveryConsumer() {
    var registry = new DefaultListableBeanFactory();
    ModuleClientFactoryBean.register(registry, CallerIdentity.class);
    registry
        .getBeanDefinition(CallerIdentity.class.getName() + "#serviceRouterProxy")
        .setScope("singleton");
    assertThatThrownBy(() -> ModuleClientFactoryBean.register(registry, CallerIdentity.class))
        .isInstanceOf(ModuleCompositionException.class)
        .hasMessageContaining("CONFLICTING_MODULE_CLIENT");
  }
}
