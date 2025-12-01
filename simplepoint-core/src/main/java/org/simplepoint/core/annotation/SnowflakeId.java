/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.annotation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hibernate.annotations.IdGeneratorType;
import org.simplepoint.core.generator.SnowflakeIdGenerator;

/**
 * An annotation to specify the usage of a Snowflake ID generator.
 * This annotation indicates that the annotated field or method will use
 * a Snowflake-based ID generation strategy.
 *
 * <p>The annotation is retained at runtime and can be applied to fields
 * or methods.
 * </p>
 */

@Retention(RUNTIME)
@IdGeneratorType(SnowflakeIdGenerator.class)
@Target({FIELD, METHOD})
public @interface SnowflakeId {
}

