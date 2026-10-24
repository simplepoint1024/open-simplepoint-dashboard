package org.simplepoint.plugin.rbac.tenant.api.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/** Replays FIFO consumption and revocations without charging another purchase for revoked time. */
public final class ResourceEntitlementLedger {
  private ResourceEntitlementLedger() {}

  /** Immutable facts; input is ordered by activation time, then stable credit ID. */
  public record Credit(String id, Instant activatedAt, Integer durationDays, Instant revokedAt) {
    /** Rejects malformed history before calculating a new authorization balance. */
    public Credit {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(activatedAt, "activatedAt");
      if (id.isBlank() || durationDays != null && durationDays <= 0
          || revokedAt != null && revokedAt.isBefore(activatedAt)) {
        throw new IllegalArgumentException("Invalid entitlement history");
      }
    }
  }

  private record Revocation(String id, Instant at) {}

  /**
   * Streams ordered history. Only outstanding credits and scheduled revocations are retained.
   * The returned window describes remaining use from the requested instant, not a historical receipt.
   */
  public static Optional<ResourceEntitlementWindow> remaining(Iterator<Credit> history, Instant at) {
    Objects.requireNonNull(history, "history");
    Objects.requireNonNull(at, "at");
    var replay = new Replay();
    Credit previous = null;
    while (history.hasNext()) {
      Credit credit = history.next();
      if (credit.activatedAt().isAfter(at) || previous != null
          && (credit.activatedAt().isBefore(previous.activatedAt())
              || credit.activatedAt().equals(previous.activatedAt()) && credit.id().compareTo(previous.id()) <= 0)) {
        throw new IllegalArgumentException("Entitlement history must be ordered and cannot be in the future");
      }
      replay.advance(credit.activatedAt());
      replay.outstanding.put(credit.id(), credit.durationDays() == null ? null : Duration.ofDays(credit.durationDays()));
      if (credit.revokedAt() != null) {
        replay.revocations.add(new Revocation(credit.id(), credit.revokedAt()));
      }
      previous = credit;
    }
    replay.advance(at);
    Duration remaining = Duration.ZERO;
    for (Duration duration : replay.outstanding.values()) {
      if (duration == null) {
        return Optional.of(new ResourceEntitlementWindow(at, null));
      }
      remaining = remaining.plus(duration);
    }
    return remaining.isZero() ? Optional.empty()
        : Optional.of(new ResourceEntitlementWindow(at, at.plus(remaining)));
  }

  private static final class Replay {
    private final LinkedHashMap<String, Duration> outstanding = new LinkedHashMap<>();
    private final PriorityQueue<Revocation> revocations = new PriorityQueue<>(
        Comparator.comparing(Revocation::at).thenComparing(Revocation::id));
    private Instant cursor;

    void advance(Instant at) {
      while (!revocations.isEmpty() && !revocations.peek().at().isAfter(at)) {
        Revocation revocation = revocations.remove();
        consumeUntil(revocation.at());
        outstanding.remove(revocation.id());
      }
      consumeUntil(at);
    }

    private void consumeUntil(Instant at) {
      Duration elapsed = cursor == null ? Duration.ZERO : Duration.between(cursor, at);
      var iterator = outstanding.entrySet().iterator();
      while (!elapsed.isZero() && iterator.hasNext()) {
        var entry = iterator.next();
        Duration duration = entry.getValue();
        if (duration == null) {
          break;
        }
        if (elapsed.compareTo(duration) >= 0) {
          elapsed = elapsed.minus(duration);
          iterator.remove();
        } else {
          entry.setValue(duration.minus(elapsed));
          elapsed = Duration.ZERO;
        }
      }
      cursor = at;
    }
  }
}
