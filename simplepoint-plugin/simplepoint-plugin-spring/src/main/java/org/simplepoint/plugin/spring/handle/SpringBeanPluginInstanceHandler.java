/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.handle;

import java.util.List;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstanceHandler;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Handles the registration and management of Spring Beans for plugin instances.
 * 处理插件实例的 Spring Bean 注册和管理
 */
public record SpringBeanPluginInstanceHandler(ApplicationContext applicationContext)
    implements PluginInstanceHandler {

  /**
   * Default constructor.
   * 默认构造函数
   */
  public SpringBeanPluginInstanceHandler {
  }

  /**
   * Registers a new Spring Bean with the given name.
   * 使用指定的名称注册新的 Spring Bean
   *
   * @param beanName the name of the bean to register
   *                 要注册的 Bean 名称
   * @param bean     the bean instance to register
   *                 要注册的 Bean 实例
   */
  public void registerBean(String beanName, Object bean) {
    unregisterBean(beanName);

    if (applicationContext instanceof ConfigurableApplicationContext cac
        && cac.getBeanFactory() instanceof DefaultListableBeanFactory dlbf) {
      // Ensure bean is fully initialized with post-processors (including AOP)
      Object initialized = bean;
      if (!(bean instanceof org.springframework.aop.framework.Advised)) {
        initialized = applicationContext.getAutowireCapableBeanFactory()
            .initializeBean(bean, beanName);
      }
      dlbf.registerSingleton(beanName, initialized);
    } else {
      // Fallback
      applicationContext.getAutowireCapableBeanFactory().initializeBean(bean, beanName);
      ((DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory())
          .registerSingleton(beanName, bean);
    }
  }

  /**
   * Creates a new Spring Bean instance of the specified class.
   * 创建指定类的新 Spring Bean 实例
   *
   * @param <T>       the type of bean to create
   *                  要创建的 Bean 类型
   * @param beanClass the class of the bean to create
   *                  要创建的 Bean 类
   * @return the newly created bean instance 新创建的 Bean 实例
   */
  @SuppressWarnings("all")
  public <T> T createBean(Class<T> beanClass) {
    // Create with full autowiring & post-processors to allow AOP proxies
    return (T) applicationContext.getAutowireCapableBeanFactory()
        .createBean(beanClass, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, true);
  }

  /**
   * Unregisters a Spring Bean with the specified name.
   * 取消注册指定名称的 Spring Bean
   *
   * @param beanName the name of the bean to unregister
   *                 要取消注册的 Bean 名称
   */
  public void unregisterBean(String beanName) {
    if (((ConfigurableApplicationContext) applicationContext).getBeanFactory()
        instanceof DefaultListableBeanFactory defaultListableBeanFactory) {
      if (defaultListableBeanFactory.containsSingleton(beanName)) {
        defaultListableBeanFactory.destroySingleton(beanName);
      }
      if (defaultListableBeanFactory.containsBeanDefinition(beanName)) {
        defaultListableBeanFactory.removeBeanDefinition(beanName);
      }
    }
  }

  /**
   * Returns the default plugin instance groups.
   * 返回默认的插件实例分组
   *
   * @return a list of plugin groups 插件分组列表
   */
  @Override
  public List<String> groups() {
    return List.of("service");
  }

  /**
   * Handles the initialization of a plugin instance.
   * 处理插件实例的初始化
   *
   * @param instance the plugin instance to handle
   *                 要处理的插件实例
   */
  @Override
  public void handle(Plugin.PluginInstance instance) {
    instance.instance(createBean(instance.getClazz()));
    registerBean(instance.getName(), instance.getInstance());
  }

  /**
   * Rolls back the registration of a plugin instance.
   * 回滚插件实例的注册
   *
   * @param instance the plugin instance to roll back
   *                 要回滚的插件实例
   */
  @Override
  public void rollback(Plugin.PluginInstance instance) {
    unregisterBean(instance.getName());
  }
}
