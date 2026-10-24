package org.simplepoint.security;

/** Explicit directory intent; distribution must never be used to expand role grant candidates. */
public enum ResourceDirectoryPurpose {
  AUTHORIZATION,
  DISTRIBUTION;

  /** Missing intent preserves the existing authorization directory contract. */
  public static ResourceDirectoryPurpose parse(String value) {
    if (value == null || value.isBlank()) {
      return AUTHORIZATION;
    }
    try {
      return valueOf(value);
    } catch (IllegalArgumentException exception) {
      throw new org.simplepoint.core.query.InvalidQueryException(
          "Unknown resource directory purpose");
    }
  }
}
