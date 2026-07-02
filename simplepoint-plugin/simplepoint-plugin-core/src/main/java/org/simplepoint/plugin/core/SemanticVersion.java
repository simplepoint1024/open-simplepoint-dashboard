/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.plugin.core;

import java.util.Objects;

/**
 * Minimal semantic version representation used by plugin compatibility checks.
 */
final class SemanticVersion implements Comparable<SemanticVersion> {

  private final int major;
  private final int minor;
  private final int patch;

  private SemanticVersion(int major, int minor, int patch) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
  }

  static SemanticVersion parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Version is required");
    }
    String normalized = value.trim();
    if (normalized.startsWith("v") || normalized.startsWith("V")) {
      normalized = normalized.substring(1);
    }
    normalized = normalized.split("[-+]", 2)[0];
    String[] parts = normalized.split("\\.");
    int major = parsePart(parts, 0);
    int minor = parsePart(parts, 1);
    int patch = parsePart(parts, 2);
    return new SemanticVersion(major, minor, patch);
  }

  SemanticVersion nextMajor() {
    return new SemanticVersion(major + 1, 0, 0);
  }

  SemanticVersion nextMinor() {
    return new SemanticVersion(major, minor + 1, 0);
  }

  SemanticVersion nextPatch() {
    return new SemanticVersion(major, minor, patch + 1);
  }

  SemanticVersion caretUpperBound() {
    if (major > 0) {
      return nextMajor();
    }
    if (minor > 0) {
      return nextMinor();
    }
    return nextPatch();
  }

  SemanticVersion tildeUpperBound(int specifiedParts) {
    if (specifiedParts <= 1) {
      return nextMajor();
    }
    return nextMinor();
  }

  private static int parsePart(String[] parts, int index) {
    if (index >= parts.length || parts[index].isBlank()) {
      return 0;
    }
    return Integer.parseInt(parts[index]);
  }

  @Override
  public int compareTo(SemanticVersion other) {
    int majorCompare = Integer.compare(this.major, other.major);
    if (majorCompare != 0) {
      return majorCompare;
    }
    int minorCompare = Integer.compare(this.minor, other.minor);
    if (minorCompare != 0) {
      return minorCompare;
    }
    return Integer.compare(this.patch, other.patch);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SemanticVersion other)) {
      return false;
    }
    return major == other.major && minor == other.minor && patch == other.patch;
  }

  @Override
  public int hashCode() {
    return Objects.hash(major, minor, patch);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
