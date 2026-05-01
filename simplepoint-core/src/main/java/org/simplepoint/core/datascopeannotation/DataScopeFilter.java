/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.datascopeannotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method for data scope (row-level) filtering.
 *
 * <p>When present, an AOP aspect reads the current user's effective
 * {@code DataScopeType} from {@link org.simplepoint.core.AuthorizationContextHolder}
 * and stores a {@link DataScopeCondition} in {@link DataScopeContext}.
 * The repository layer can then read from {@link DataScopeContext} to apply
 * the appropriate row-level predicate (e.g. a JPA {@code Specification}).</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * @DataScopeFilter(deptField = "deptId", ownerField = "createdBy")
 * public Page<Order> listOrders(Map<String, String> attributes, Pageable pageable) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScopeFilter {

  /**
   * The entity field name that holds the department/organization ID.
   * Used for DEPT, DEPT_AND_BELOW, and CUSTOM scope types.
   *
   * @return the department field name
   */
  String deptField() default "deptId";

  /**
   * The entity field name that holds the owner/creator user ID.
   * Used for SELF scope type.
   *
   * @return the owner field name
   */
  String ownerField() default "createdBy";
}
