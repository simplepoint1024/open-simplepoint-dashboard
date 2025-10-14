/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.api.lock;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;

/**
 * Distributed Locking.
 *
 * @param <L> Locking
 */
public interface DistributedLocking<L extends Lock> {
  /**
   * Execute with lock.
   *
   * @param key       lock key
   * @param runnable  runnable
   * @param waitTime  waitTime
   * @param leaseTime leaseTime
   * @param <V>       result type
   * @return result
   * @throws Exception Exception
   */
  <V> V executeWithLock(String key, Callable<V> runnable, long waitTime, long leaseTime)
      throws Exception;

  /**
   * isLocked.
   *
   * @param key .
   * @return .
   */
  boolean isLocked(String key);

  /**
   * forceUnlock.
   *
   * @param key .
   * @return .
   */
  boolean forceUnlock(String key);

  /**
   * getLock.
   *
   * @param key .
   * @return .
   */
  L getLock(String key);
}
