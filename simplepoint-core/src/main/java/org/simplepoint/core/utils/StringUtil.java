/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.core.utils;

import java.util.HashSet;
import java.util.Set;

/**
 * A utility class for string manipulation and conversion.
 */
public class StringUtil {

  /**
   * Splits a string into two parts at the last occurrence of the specified delimiter.
   *
   * @param input      the input string to be split
   * @param delimiter  the delimiter to split the string on
   * @return an array containing two parts: the part before the last delimiter and the part after it
   */
  public static String[] splitLast(String input, String delimiter) {
    int lastIndex = input.lastIndexOf(delimiter);
    if (lastIndex == -1) {
      // Return the input string as a single part if the delimiter is not present
      return new String[] {input};
    }
    // Split the string into two parts
    String firstPart = input.substring(0, lastIndex);
    String lastPart = input.substring(lastIndex + 1);
    return new String[] {firstPart, lastPart};
  }

  /**
   * Converts a comma-separated string into a set of Long values.
   *
   * @param input the comma-separated string
   * @return a set of Long values parsed from the input string
   * @throws NumberFormatException if any part of the string cannot be parsed into a Long
   */
  public static Set<Long> stringToLongSet(String input) {
    Set<Long> set = new HashSet<>();
    // Split the input string using the splitLast method
    String[] parts = splitLast(input, ",");
    for (String part : parts) {
      set.add(Long.parseLong(part));
    }
    return set;
  }

  /**
   * Converts a comma-separated string into a set of String values.
   *
   * @param input the comma-separated string
   * @return a set of String values parsed from the input string
   */
  public static Set<String> stringToSet(String input) {
    Set<String> set = new HashSet<>();
    // Split the input string using the splitLast method
    String[] parts = splitLast(input, ",");
    for (String part : parts) {
      set.add(part.trim()); // Trim whitespace around each part
    }
    return set;
  }
}
