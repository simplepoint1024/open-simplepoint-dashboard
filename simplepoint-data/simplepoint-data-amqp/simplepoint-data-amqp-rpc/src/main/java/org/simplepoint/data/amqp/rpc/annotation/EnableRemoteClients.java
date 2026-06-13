/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.rpc.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.simplepoint.data.amqp.rpc.RemoteClientOverrideBeanFactoryPostProcessor;
import org.simplepoint.data.amqp.rpc.RemoteClientsRegistrar;
import org.springframework.context.annotation.Import;

/**
 * Enables remote contract proxy registration for this transport.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({RemoteClientsRegistrar.class, RemoteClientOverrideBeanFactoryPostProcessor.class})
public @interface EnableRemoteClients {

  /**
   * Specifies base packages to scan.
   *
   * @return base package names
   */
  String[] value() default {};

  /**
   * Specifies base packages to scan.
   *
   * @return base package names
   */
  String[] basePackages() default {};

  /**
   * Specifies marker classes whose packages should be scanned.
   *
   * @return base package marker classes
   */
  Class<?>[] basePackageClasses() default {};
}
