package org.simplepoint.data.amqp.rpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.simplepoint.remoting.RemoteContract;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class RemoteClientOverrideBeanFactoryPostProcessorTest {

  private final RemoteClientOverrideBeanFactoryPostProcessor postProcessor =
      new RemoteClientOverrideBeanFactoryPostProcessor();

  @Test
  void postProcessBeanFactoryShouldRemoveRemoteProxyWhenLocalImplementationExists() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    postProcessor.setBeanClassLoader(getClass().getClassLoader());
    beanFactory.registerBeanDefinition(SampleApi.class.getName(),
        RemoteProxyFactory.proxy(getClass().getClassLoader(), SampleApi.class, Map.of("name", "sample")));
    beanFactory.registerBeanDefinition("sampleRemoteService",
        BeanDefinitionBuilder.genericBeanDefinition(SampleRemoteService.class).getBeanDefinition());

    postProcessor.postProcessBeanFactory(beanFactory);

    assertFalse(beanFactory.containsBeanDefinition(SampleApi.class.getName()));
    assertArrayEquals(new String[] {"sampleRemoteService"},
        beanFactory.getBeanNamesForType(SampleApi.class, false, false));
  }

  @Test
  void postProcessBeanFactoryShouldKeepRemoteProxyWhenNoLocalImplementationExists() {
    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    postProcessor.setBeanClassLoader(getClass().getClassLoader());
    beanFactory.registerBeanDefinition(SampleApi.class.getName(),
        RemoteProxyFactory.proxy(getClass().getClassLoader(), SampleApi.class, Map.of("name", "sample")));

    postProcessor.postProcessBeanFactory(beanFactory);

    assertTrue(beanFactory.containsBeanDefinition(SampleApi.class.getName()));
    assertArrayEquals(new String[] {SampleApi.class.getName()},
        beanFactory.getBeanNamesForType(SampleApi.class, false, false));
  }

  @RemoteContract(name = "sample")
  interface SampleApi {
    String ping();
  }

  @RemoteProvider
  static final class SampleRemoteService implements SampleApi {

    @Override
    public String ping() {
      return "pong";
    }
  }
}
