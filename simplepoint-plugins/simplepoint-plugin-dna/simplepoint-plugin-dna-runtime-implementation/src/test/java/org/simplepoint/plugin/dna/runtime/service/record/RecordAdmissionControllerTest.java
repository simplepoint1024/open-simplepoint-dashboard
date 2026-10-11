package org.simplepoint.plugin.dna.runtime.service.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;
import org.simplepoint.plugin.dna.runtime.configuration.DnaRuntimeExecutionProperties;

class RecordAdmissionControllerTest {

  @Test
  void shouldEnforceAndReleaseThePerTenantConcurrencyBudget() {
    DnaRuntimeExecutionProperties properties = new DnaRuntimeExecutionProperties();
    properties.setMaxConcurrentPerTenant(1);
    RecordAdmissionController controller = new RecordAdmissionController(properties);

    try (RecordAdmissionController.Permit ignored = controller.enter("tenant-a")) {
      assertThatThrownBy(() -> controller.enter("tenant-a"))
          .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
              assertThat(exception.errorCode()).isEqualTo(DnaRuntimeErrorCode.RECORD_RATE_LIMITED));
      try (RecordAdmissionController.Permit otherTenant = controller.enter("tenant-b")) {
        assertThat(otherTenant).isNotNull();
      }
    }

    try (RecordAdmissionController.Permit releasedSlot = controller.enter("tenant-a")) {
      assertThat(releasedSlot).isNotNull();
    }
  }
}
