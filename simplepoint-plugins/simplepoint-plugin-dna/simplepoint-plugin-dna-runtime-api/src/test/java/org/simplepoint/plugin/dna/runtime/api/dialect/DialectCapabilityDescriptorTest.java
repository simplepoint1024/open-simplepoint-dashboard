package org.simplepoint.plugin.dna.runtime.api.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

class DialectCapabilityDescriptorTest {

  private static final DatabaseIdentity DATABASE =
      new DatabaseIdentity("ExampleDB", "1.0", 1, 0);

  @Test
  void rejectsMissingAndUnsupportedOperationsBeforeExecution() {
    DialectCapabilityDescriptor descriptor = new DialectCapabilityDescriptor(
        "example",
        DATABASE,
        DialectSupportTier.EXPERIMENTAL,
        Map.of(DialectOperation.DROP_TABLE, DialectCapability.unsupported())
    );

    assertCode(
        () -> descriptor.requireSupported(DialectOperation.CREATE_TABLE),
        DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN
    );
    assertCode(
        () -> descriptor.requireSupported(DialectOperation.DROP_TABLE),
        DnaRuntimeErrorCode.DIALECT_OPERATION_UNSUPPORTED
    );
  }

  @Test
  void limitedSupportRequiresMachineReadableConstraints() {
    assertThatThrownBy(() -> new DialectCapability(
        CapabilitySupportLevel.SUPPORTED_WITH_LIMITS,
        Set.of()
    )).isInstanceOf(IllegalArgumentException.class);

    DialectCapability capability = new DialectCapability(
        CapabilitySupportLevel.SUPPORTED_WITH_LIMITS,
        Set.of(new DialectCapabilityConstraint(
            CapabilityConstraintType.MAX_IDENTIFIER_LENGTH,
            CapabilityConstraintOperator.LESS_THAN_OR_EQUAL,
            "63"
        ))
    );

    assertThat(capability.constraints()).hasSize(1);
  }

  @Test
  void readOnlyTierCannotAccidentallyDeclareMutationSupport() {
    assertThatThrownBy(() -> new DialectCapabilityDescriptor(
        "readonly",
        DATABASE,
        DialectSupportTier.READ_ONLY,
        Map.of(DialectOperation.CREATE_TABLE, DialectCapability.supported())
    )).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("READ_ONLY");
  }

  private static void assertCode(
      final Runnable operation,
      final DnaRuntimeErrorCode expected
  ) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(DnaRuntimeException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(expected));
  }
}
