/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.remoting;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a transport-neutral remoting contract.
 * Interfaces annotated with this contract can be backed by a local provider or a remote proxy.
 */
@Inherited
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RemoteContract {

  /**
   * Logical service capability name, for example {@code security.user}.
   *
   * @return the logical service capability name
   */
  String name();

  /**
   * Contract version used for provider discovery and compatibility checks.
   *
   * @return the contract version
   */
  String version() default "1";
}
