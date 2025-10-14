/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * A Spring component that provides static access to the {@link ApplicationContext}.
 * Enables retrieval of beans and application context from anywhere in the application.
 */
@Component
public class ApplicationContextProvider implements ApplicationContextAware {

  /**
   * A static reference to the Spring {@link ApplicationContext}.
   */
  private static ApplicationContext context;

  /**
   * Sets the {@link ApplicationContext} instance.
   * This method is automatically called by Spring during application initialization.
   *
   * @param applicationContext the {@link ApplicationContext} to set
   * @throws BeansException if the context initialization fails
   */
  @Override
  public void setApplicationContext(@NonNull ApplicationContext applicationContext)
      throws BeansException {
    ApplicationContextProvider.context = applicationContext;
  }

  /**
   * Retrieves the static {@link ApplicationContext} instance.
   *
   * @return the current {@link ApplicationContext}, or null if it has not been initialized
   */
  public static ApplicationContext getApplicationContext() {
    return context;
  }

  /**
   * Retrieves a bean from the {@link ApplicationContext} by its name.
   *
   * @param beanName the name of the bean to retrieve
   * @return the bean instance associated with the given name
   */
  public static Object getBean(String beanName) {
    return ApplicationContextProvider.getApplicationContext().getBean(beanName);
  }

  /**
   * Retrieves a bean from the {@link ApplicationContext} by its class type.
   *
   * @param <T>   the type of the bean to retrieve
   * @param clazz the class type of the bean to retrieve
   * @return the bean instance of the specified type
   */
  public static <T> T getBean(Class<T> clazz) {
    return ApplicationContextProvider.getApplicationContext().getBean(clazz);
  }
}
