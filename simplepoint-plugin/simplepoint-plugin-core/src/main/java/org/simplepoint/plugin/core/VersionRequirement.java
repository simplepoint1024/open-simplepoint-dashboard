/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Version requirement expression used by plugin compatibility checks.
 */
final class VersionRequirement {

  private final List<RangeCheck> checks;

  private VersionRequirement(List<RangeCheck> checks) {
    this.checks = checks;
  }

  static VersionRequirement parse(String expression) {
    if (expression == null || expression.isBlank() || "*".equals(expression.trim())) {
      return new VersionRequirement(List.of());
    }
    List<RangeCheck> checks = new ArrayList<>();
    for (String token : expression.replace(",", " ").trim().split("\\s+")) {
      if (token.isBlank() || "*".equals(token)) {
        continue;
      }
      checks.addAll(parseToken(token));
    }
    return new VersionRequirement(checks);
  }

  boolean matches(String version) {
    SemanticVersion parsed = SemanticVersion.parse(version);
    for (RangeCheck check : checks) {
      if (!check.matches(parsed)) {
        return false;
      }
    }
    return true;
  }

  private static List<RangeCheck> parseToken(String token) {
    if (token.startsWith("^")) {
      SemanticVersion base = SemanticVersion.parse(token.substring(1));
      return List.of(RangeCheck.greaterThanOrEqual(base), RangeCheck.lessThan(base.caretUpperBound()));
    }
    if (token.startsWith("~")) {
      String version = token.substring(1);
      SemanticVersion base = SemanticVersion.parse(version);
      return List.of(RangeCheck.greaterThanOrEqual(base), RangeCheck.lessThan(base.tildeUpperBound(specifiedParts(version))));
    }
    if (token.contains("x") || token.contains("X") || token.contains("*")) {
      return wildcard(token);
    }
    for (Operator operator : Operator.ordered()) {
      if (token.startsWith(operator.symbol)) {
        return List.of(new RangeCheck(operator, SemanticVersion.parse(token.substring(operator.symbol.length()))));
      }
    }
    return List.of(new RangeCheck(Operator.EQUAL, SemanticVersion.parse(token)));
  }

  private static List<RangeCheck> wildcard(String token) {
    String[] parts = token.replace("*", "x").replace("X", "x").split("\\.");
    int major = numericOrZero(parts, 0);
    int minor = numericOrZero(parts, 1);
    SemanticVersion lower = SemanticVersion.parse(major + "." + minor + ".0");
    SemanticVersion upper = parts.length <= 1 || isWildcard(parts[1])
        ? lower.nextMajor()
        : lower.nextMinor();
    return List.of(RangeCheck.greaterThanOrEqual(lower), RangeCheck.lessThan(upper));
  }

  private static int specifiedParts(String version) {
    return version.split("\\.").length;
  }

  private static int numericOrZero(String[] parts, int index) {
    if (index >= parts.length || isWildcard(parts[index])) {
      return 0;
    }
    return Integer.parseInt(parts[index]);
  }

  private static boolean isWildcard(String value) {
    return "x".equalsIgnoreCase(value) || "*".equals(value);
  }

  private record RangeCheck(Operator operator, SemanticVersion version) {

    private static RangeCheck greaterThanOrEqual(SemanticVersion version) {
      return new RangeCheck(Operator.GREATER_THAN_OR_EQUAL, version);
    }

    private static RangeCheck lessThan(SemanticVersion version) {
      return new RangeCheck(Operator.LESS_THAN, version);
    }

    private boolean matches(SemanticVersion candidate) {
      int comparison = candidate.compareTo(version);
      return switch (operator) {
        case GREATER_THAN -> comparison > 0;
        case GREATER_THAN_OR_EQUAL -> comparison >= 0;
        case LESS_THAN -> comparison < 0;
        case LESS_THAN_OR_EQUAL -> comparison <= 0;
        case EQUAL -> comparison == 0;
      };
    }
  }

  private enum Operator {
    GREATER_THAN_OR_EQUAL(">="),
    LESS_THAN_OR_EQUAL("<="),
    GREATER_THAN(">"),
    LESS_THAN("<"),
    EQUAL("=");

    private final String symbol;

    Operator(String symbol) {
      this.symbol = symbol;
    }

    private static List<Operator> ordered() {
      return List.of(GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL, GREATER_THAN, LESS_THAN, EQUAL);
    }
  }
}
