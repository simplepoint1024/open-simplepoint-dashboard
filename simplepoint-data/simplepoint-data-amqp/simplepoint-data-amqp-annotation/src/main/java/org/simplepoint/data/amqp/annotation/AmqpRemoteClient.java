/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.data.amqp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A custom annotation to designate a class as an AMQP (Advanced Message Queuing Protocol)
 * remote client. This annotation provides configuration for the target endpoint and
 * an optional fallback class.
 */
@Inherited
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AmqpRemoteClient {

  /**
   * Specifies the target AMQP destination.
   *
   * @return the endpoint or destination to which the client communicates
   */
  String to();

  /**
   * Specifies a fallback class to handle failures.
   * If not set, defaults to {@code void.class}, indicating no fallback is provided.
   *
   * @return the fallback class to be used
   */
  Class<?> fallback() default void.class;
}
