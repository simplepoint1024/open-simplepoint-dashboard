/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.code;

/**
 * code maker.
 */
public interface RuleCodeMaker {
  /**
   * maker code.
   *
   * @param depth code depth
   * @return code
   */
  String make(Integer depth);

  /**
   * maker next code.
   *
   * @param current current code
   * @param depth   code depth
   * @return code
   */
  String next(String current, Integer depth);

  /**
   * maker last code.
   *
   * @param current current code
   * @param depth   code depth
   * @return code
   */
  String last(String current, Integer depth);
}
