/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.datascopeannotation;

/**
 * ThreadLocal holder for the data scope condition active in the current request thread.
 *
 * <p>An AOP aspect populates this before executing a {@link DataScopeFilter}-annotated
 * method, and clears it in a {@code finally} block afterwards. The repository layer
 * reads from this holder to build the appropriate row-level query predicate.</p>
 */
public final class DataScopeContext {

  private static final ThreadLocal<DataScopeCondition> HOLDER = new ThreadLocal<>();

  private DataScopeContext() {
  }

  /**
   * Sets the current data scope condition.
   *
   * @param condition the condition to set
   */
  public static void set(DataScopeCondition condition) {
    HOLDER.set(condition);
  }

  /**
   * Returns the current data scope condition, or {@code null} if none is active.
   *
   * @return the current condition, or null
   */
  public static DataScopeCondition get() {
    return HOLDER.get();
  }

  /**
   * Clears the current data scope condition.
   * Must be called in a {@code finally} block after the scoped operation completes.
   */
  public static void clear() {
    HOLDER.remove();
  }
}
