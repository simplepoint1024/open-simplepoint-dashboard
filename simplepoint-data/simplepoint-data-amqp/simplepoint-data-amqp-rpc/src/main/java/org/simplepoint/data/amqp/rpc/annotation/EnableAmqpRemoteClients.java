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
import org.simplepoint.data.amqp.rpc.RemoteClientsRegistrar;
import org.springframework.context.annotation.Import;

/**
 * A custom annotation to enable AMQP (Advanced Message Queuing Protocol) remote client functionality.
 * This annotation allows the registration of remote clients based on the specified packages and classes.
 * It integrates with the Spring framework through the {@link Import} mechanism to register
 * {@link RemoteClientsRegistrar}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({RemoteClientsRegistrar.class})
public @interface EnableAmqpRemoteClients {

  /**
   * Specifies the names of the remote client beans to be registered.
   *
   * @return an array of bean names for the remote clients
   */
  String[] value() default {};

  /**
   * Specifies the base packages to scan for remote clients.
   * Classes annotated with the relevant client annotations will be automatically registered.
   *
   * @return an array of package names to scan
   */
  String[] basePackages() default {};

  /**
   * Specifies base package marker classes to scan for remote clients.
   * The packages containing the specified classes will be included in the scanning process.
   *
   * @return an array of marker classes for base packages
   */
  Class<?>[] basePackageClasses() default {};
}
