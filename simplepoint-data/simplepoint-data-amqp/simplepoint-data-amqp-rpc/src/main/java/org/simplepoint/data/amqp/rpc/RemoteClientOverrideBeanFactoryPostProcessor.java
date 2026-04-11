/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Removes generated AMQP client proxies when the same contract already has a local bean.
 */
@Slf4j
public class RemoteClientOverrideBeanFactoryPostProcessor
    implements BeanFactoryPostProcessor, BeanClassLoaderAware, Ordered {

  private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

  @Override
  public void setBeanClassLoader(final ClassLoader beanClassLoader) {
    this.beanClassLoader = beanClassLoader;
  }

  @Override
  public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) {
    if (!(beanFactory instanceof BeanDefinitionRegistry registry)) {
      return;
    }
    for (String beanName : beanFactory.getBeanDefinitionNames()) {
      BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
      Object remoteInterface = beanDefinition.getAttribute(
          RemoteProxyFactory.RemoteInvocationHandler.REMOTE_INTERFACE_ATTRIBUTE);
      if (!(remoteInterface instanceof String interfaceName) || interfaceName.isBlank()) {
        continue;
      }
      Class<?> interfaceClass = ClassUtils.resolveClassName(interfaceName, beanClassLoader);
      if (!hasLocalImplementation(beanFactory, beanName, interfaceClass)) {
        continue;
      }
      registry.removeBeanDefinition(beanName);
      log.debug("Skipping AMQP proxy [{}] because local implementation for [{}] is available",
          beanName, interfaceName);
    }
  }

  private boolean hasLocalImplementation(final ConfigurableListableBeanFactory beanFactory,
                                         final String proxyBeanName,
                                         final Class<?> interfaceClass) {
    for (String candidateName : beanFactory.getBeanNamesForType(interfaceClass, false, false)) {
      if (proxyBeanName.equals(candidateName)) {
        continue;
      }
      if (!beanFactory.containsBeanDefinition(candidateName)) {
        return true;
      }
      BeanDefinition candidateDefinition = beanFactory.getBeanDefinition(candidateName);
      if (candidateDefinition.isAbstract() || !candidateDefinition.isAutowireCandidate()) {
        continue;
      }
      if (candidateDefinition.getAttribute(RemoteProxyFactory.RemoteInvocationHandler.REMOTE_INTERFACE_ATTRIBUTE)
          != null) {
        continue;
      }
      Class<?> candidateType = beanFactory.getType(candidateName, false);
      if (candidateType != null && interfaceClass.isAssignableFrom(candidateType)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
