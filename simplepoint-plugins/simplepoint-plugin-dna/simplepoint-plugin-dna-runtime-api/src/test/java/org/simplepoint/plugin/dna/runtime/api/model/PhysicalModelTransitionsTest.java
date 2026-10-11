package org.simplepoint.plugin.dna.runtime.api.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class PhysicalModelTransitionsTest {

  @Test
  void permitsPublicationPathAndNewDraftFromActiveModel() {
    assertThat(PhysicalModelTransitions.canTransition(
        PhysicalModelStatus.DRAFT,
        PhysicalModelStatus.VALIDATING
    )).isTrue();
    assertThat(PhysicalModelTransitions.canTransition(
        PhysicalModelStatus.ACTIVE,
        PhysicalModelStatus.DRAFT
    )).isTrue();
    assertThat(PhysicalModelTransitions.canTransition(
        PhysicalModelStatus.READY,
        PhysicalModelStatus.FAILED
    )).isTrue();
  }

  @Test
  void rejectsUndeclaredStateChangesWithStableCode() {
    assertThatThrownBy(() -> PhysicalModelTransitions.requireTransition(
        PhysicalModelStatus.DRAFT,
        PhysicalModelStatus.ACTIVE
    )).isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
        assertThat(exception.errorCode())
            .isEqualTo(DnaRuntimeErrorCode.PHYSICAL_MODEL_STATE_CONFLICT));
  }
}
