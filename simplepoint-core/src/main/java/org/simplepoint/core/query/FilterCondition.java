package org.simplepoint.core.query;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Legacy wire adapter. Split the operator prefix, never the user's text or ISO timestamp. */
public record FilterCondition(String operator, String value) {
  public static final List<String> OPERATORS =
      List.of(
          "like",
          "not:like",
          "equals",
          "not:equals",
          "in",
          "not:in",
          "between",
          "not:between",
          "than:greater",
          "than:less",
          "than:equal:greater",
          "than:equal:less",
          "is:null",
          "is:not:null");
  public static final int MAX_VALUE_LENGTH = 8192;

  /** Supports existing bare equality values and registered custom operators. */
  public static FilterCondition parse(String encoded, Collection<String> operators) {
    if (encoded == null || encoded.isBlank() || encoded.length() > MAX_VALUE_LENGTH) {
      throw new InvalidQueryException("筛选值不能为空或超过长度限制");
    }
    if (encoded.equals("is:null") || encoded.equals("is:not:null")) {
      return new FilterCondition(encoded, "");
    }
    String op =
        operators.stream()
            .filter(key -> encoded.startsWith(key + ":"))
            .max(Comparator.comparingInt(String::length))
            .orElse(null);
    if (op != null) {
      String value = encoded.substring(op.length() + 1);
      // ConditionsBuilder historically appended the example value to null operators.
      if (op.equals("is:null") || op.equals("is:not:null")) {
        return new FilterCondition(op, "");
      }
      if (value.isBlank()) {
        throw new InvalidQueryException("筛选操作符和值不匹配");
      }
      return new FilterCondition(op, value);
    }
    if (encoded.contains(":")) {
      throw new InvalidQueryException("不支持的筛选操作符；含冒号的等值文本请使用 equals 前缀");
    }
    return new FilterCondition("equals", encoded);
  }

  /** Parses the built-in platform operators. */
  public static FilterCondition parse(String encoded) {
    return parse(encoded, OPERATORS);
  }
}
