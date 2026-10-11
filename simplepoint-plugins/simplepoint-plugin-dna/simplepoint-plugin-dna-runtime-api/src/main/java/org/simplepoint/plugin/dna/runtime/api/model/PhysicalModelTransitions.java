package org.simplepoint.plugin.dna.runtime.api.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Single source of truth for physical model state transitions. */
public final class PhysicalModelTransitions {

  private static final Map<PhysicalModelStatus, Set<PhysicalModelStatus>> ALLOWED =
      allowedTransitions();

  private PhysicalModelTransitions() {
  }

  /** Returns whether the declared lifecycle permits a state change. */
  public static boolean canTransition(
      final PhysicalModelStatus source,
      final PhysicalModelStatus target
  ) {
    return source != null && target != null && ALLOWED.get(source).contains(target);
  }

  /** Rejects an undeclared lifecycle change with a stable conflict code. */
  public static void requireTransition(
      final PhysicalModelStatus source,
      final PhysicalModelStatus target
  ) {
    if (!canTransition(source, target)) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.PHYSICAL_MODEL_STATE_CONFLICT,
          "Physical model cannot transition from " + source + " to " + target
      );
    }
  }

  /** Returns the immutable set of legal target states. */
  public static Set<PhysicalModelStatus> targets(final PhysicalModelStatus source) {
    return source == null ? Set.of() : Set.copyOf(ALLOWED.get(source));
  }

  private static Map<PhysicalModelStatus, Set<PhysicalModelStatus>> allowedTransitions() {
    EnumMap<PhysicalModelStatus, Set<PhysicalModelStatus>> result =
        new EnumMap<>(PhysicalModelStatus.class);
    result.put(PhysicalModelStatus.DRAFT, EnumSet.of(PhysicalModelStatus.VALIDATING));
    result.put(
        PhysicalModelStatus.VALIDATING,
        EnumSet.of(PhysicalModelStatus.READY, PhysicalModelStatus.FAILED)
    );
    result.put(
        PhysicalModelStatus.READY,
        EnumSet.of(
            PhysicalModelStatus.MIGRATING,
            PhysicalModelStatus.DRAFT,
            PhysicalModelStatus.FAILED
        )
    );
    result.put(
        PhysicalModelStatus.MIGRATING,
        EnumSet.of(PhysicalModelStatus.VERIFYING, PhysicalModelStatus.FAILED)
    );
    result.put(
        PhysicalModelStatus.VERIFYING,
        EnumSet.of(PhysicalModelStatus.ACTIVE, PhysicalModelStatus.FAILED)
    );
    result.put(
        PhysicalModelStatus.ACTIVE,
        EnumSet.of(PhysicalModelStatus.DRAFT, PhysicalModelStatus.RETIRED)
    );
    result.put(
        PhysicalModelStatus.FAILED,
        EnumSet.of(
            PhysicalModelStatus.DRAFT,
            PhysicalModelStatus.VALIDATING,
            PhysicalModelStatus.RETIRED
        )
    );
    result.put(PhysicalModelStatus.RETIRED, EnumSet.noneOf(PhysicalModelStatus.class));
    return Map.copyOf(result);
  }
}
