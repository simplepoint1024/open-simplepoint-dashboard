package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Half-open resource entitlement interval; a null end explicitly means unlimited duration. */
public record ResourceEntitlementWindow(Instant startsAt, Instant expiresAt) {
  private static final Instant MAX_EXPIRY = Instant.parse("9999-12-31T23:59:59.999999Z");

  /** Rejects empty or reversed intervals at the domain boundary. */
  public ResourceEntitlementWindow {
    Objects.requireNonNull(startsAt, "startsAt");
    if (startsAt.isAfter(MAX_EXPIRY)
        || expiresAt != null && (!expiresAt.isAfter(startsAt) || expiresAt.isAfter(MAX_EXPIRY))) {
      throw new IllegalArgumentException("Invalid entitlement interval");
    }
  }

  /** Expiration is enforced at the exact end instant, independently of background jobs. */
  public boolean contains(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return !instant.isBefore(startsAt) && (expiresAt == null || instant.isBefore(expiresAt));
  }

  /** Adds one activation's days without counting inactive time or resetting existing credit. */
  public static ResourceEntitlementWindow append(
      ResourceEntitlementWindow current, Instant activatedAt, Integer durationDays) {
    Objects.requireNonNull(activatedAt, "activatedAt");
    if (durationDays != null && durationDays <= 0) {
      throw new IllegalArgumentException("Entitlement duration must be positive or unlimited");
    }
    if (current != null && activatedAt.isBefore(current.startsAt)) {
      throw new IllegalArgumentException("Activation cannot precede the existing entitlement");
    }
    boolean active = current != null && current.contains(activatedAt);
    if (active && current.expiresAt == null) {
      return current;
    }
    Instant start = active ? current.startsAt : activatedAt;
    if (durationDays == null) {
      return new ResourceEntitlementWindow(start, null);
    }
    Instant base = active ? current.expiresAt : activatedAt;
    try {
      return new ResourceEntitlementWindow(start, base.plus(Duration.ofDays(durationDays)));
    } catch (DateTimeException | ArithmeticException exception) {
      throw new IllegalArgumentException("Entitlement duration exceeds supported dates", exception);
    }
  }
}
