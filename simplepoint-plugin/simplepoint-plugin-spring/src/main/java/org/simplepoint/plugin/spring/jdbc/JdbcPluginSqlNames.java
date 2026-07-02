/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.spring.jdbc;

import java.util.regex.Pattern;

/**
 * JDBC SQL identifier helpers used by plugin infrastructure tables.
 */
public final class JdbcPluginSqlNames {

  private static final Pattern TABLE_NAME_PATTERN =
      Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)?");

  private JdbcPluginSqlNames() {
  }

  /**
   * Validates a configured table name before it is interpolated into SQL.
   *
   * @param tableName configured table name
   * @param subject   human readable table purpose
   * @return validated table name
   */
  public static String validateTableName(String tableName, String subject) {
    if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
      throw new IllegalArgumentException("Invalid " + subject + " table name: " + tableName);
    }
    return tableName;
  }
}
