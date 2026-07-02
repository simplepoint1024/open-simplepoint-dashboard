package org.simplepoint.plugin.spring.handle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.PluginsManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SpringBeanPluginInstanceHandlerTest {

  @Test
  void handleRegistersContributionBeanAndRollbackUnregistersIt() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.refresh();
      PluginsManager pluginsManager = mock(PluginsManager.class);
      SpringBeanPluginInstanceHandler handler = new SpringBeanPluginInstanceHandler(context, pluginsManager);
      Plugin.PluginInstance instance =
          new Plugin.PluginInstance("contributionBean", ContributionBean.class.getName(), "service");
      instance.classes(ContributionBean.class);

      handler.handle(instance);

      assertThat(instance.getInstance()).isInstanceOf(ContributionBean.class);
      ContributionBean bean = (ContributionBean) instance.getInstance();
      assertThat(context.getBean("contributionBean")).isSameAs(bean);
      verify(pluginsManager).registerInstallBatchValidator(same(bean));
      verify(pluginsManager).registerInstallValidator(same(bean));
      verify(pluginsManager).registerLifecycleHandler(same(bean));

      handler.rollback(instance);

      verify(pluginsManager).unregisterInstallValidator(same(bean));
      verify(pluginsManager).unregisterInstallBatchValidator(same(bean));
      verify(pluginsManager).unregisterLifecycleHandler(same(bean));
      assertThat(context.containsBean("contributionBean")).isFalse();
    }
  }

  static final class ContributionBean
      implements PluginInstallBatchValidator, PluginInstallValidator, PluginLifecycleHandler {

    @Override
    public int order() {
      return 0;
    }

    @Override
    public boolean supports(Plugin plugin) {
      return true;
    }

    @Override
    public void validate(Plugin plugin) {
    }

    @Override
    public void validate(List<Plugin> plugins) {
    }
  }
}
