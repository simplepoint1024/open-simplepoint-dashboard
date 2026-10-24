package org.simplepoint.platform.installation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Ordered installation operation, not a declaration that the entire platform is ready. */
public record InstallationPlan(
    UUID operationId,
    String target,
    long expectedGeneration,
    String releaseFingerprint,
    List<Step> steps) {
  /**
   * Captures an immutable reviewed operation; input values and secrets are not journal payloads.
   */
  public InstallationPlan {
    Objects.requireNonNull(operationId);
    identity(target);
    InstallationFingerprint.require(releaseFingerprint);
    steps = List.copyOf(steps);
    if (expectedGeneration < 0
        || expectedGeneration == Long.MAX_VALUE
        || steps.isEmpty()
        || steps.size() > 512
        || steps.stream().map(Step::id).distinct().count() != steps.size()) {
      throw new IllegalArgumentException("Invalid installation plan");
    }
  }

  /** Binds complete order, adapter versions, retry guarantees, inputs and the released artifact. */
  public String fingerprint() {
    var values = new ArrayList<String>();
    values.add(operationId.toString());
    values.add(target);
    values.add(Long.toString(expectedGeneration));
    values.add(releaseFingerprint);
    for (var step : steps) {
      values.add(step.id());
      values.add(step.kind());
      values.add(Integer.toString(step.version()));
      values.add(step.inputFingerprint());
      values.add(step.retry().name());
    }
    return InstallationFingerprint.of(values.toArray(String[]::new));
  }

  /** A stable logical action and implementation contract; no Java type names are persisted. */
  public record Step(String id, String kind, int version, String inputFingerprint, Retry retry) {
    /** Every adapter must match all these values before any operation is opened. */
    public Step {
      identity(id);
      identity(kind);
      if (version < 1) {
        throw new IllegalArgumentException("Invalid installation adapter version");
      }
      InstallationFingerprint.require(inputFingerprint);
      Objects.requireNonNull(retry);
    }
  }

  /** Only a tested durable owner operation may declare safe repeat application. */
  public enum Retry {
    RECONCILE_ONLY,
    DURABLE_IDEMPOTENT
  }

  private static void identity(String value) {
    if (value == null || value.length() > 128 || !value.matches("[a-z][a-z0-9./-]*")) {
      throw new IllegalArgumentException("Invalid installation identity");
    }
  }
}
