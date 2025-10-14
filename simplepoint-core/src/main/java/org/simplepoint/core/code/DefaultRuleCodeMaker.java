/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.code;

/**
 * DefaultRuleCodeMaker.
 */
public class DefaultRuleCodeMaker implements RuleCodeMaker {
  @Override
  public String make(Integer depth) {
    return "00000000";
  }

  @Override
  public String next(String current, Integer depth) {
    // 将当前代码转换为整数递增
    int numericValue = Integer.parseInt(current) + 1;
    return String.format("%08d", numericValue); // 保持8位格式
  }

  @Override
  public String last(String current, Integer depth) {
    var curr = Integer.parseInt(current);
    if (curr < 0) {
      return "00000000";
    }
    return String.format("%08d", curr - 1); // 保持8位格式
  }
}
