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
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * A custom annotation to mark a class as an AMQP
 * (Advanced Message Queuing Protocol) remote service.
 * It integrates with the Spring framework, inheriting
 * the properties of {@link Component} and {@link Primary}.
 * This annotation is inherited by subclasses and retained
 * at runtime for reflection-based processing.
 */
@Primary
@Component
@Inherited
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AmqpRemoteService {
}
