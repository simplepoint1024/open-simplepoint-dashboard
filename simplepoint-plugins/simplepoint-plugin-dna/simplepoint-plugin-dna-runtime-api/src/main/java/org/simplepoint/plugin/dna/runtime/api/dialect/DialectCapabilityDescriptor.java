package org.simplepoint.plugin.dna.runtime.api.dialect;

import java.util.Map;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeException;

/** Version-aware, executable capability contract for one database dialect. */
public record DialectCapabilityDescriptor(
    String dialectId,
    DatabaseIdentity database,
    DialectSupportTier supportTier,
    Map<DialectOperation, DialectCapability> capabilities
) {

  /** Creates an immutable version-aware capability descriptor. */
  public DialectCapabilityDescriptor {
    if (dialectId == null || dialectId.isBlank() || dialectId.length() > 64) {
      throw new IllegalArgumentException("dialectId must contain 1 to 64 characters");
    }
    dialectId = dialectId.trim();
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(supportTier, "supportTier");
    capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    if (supportTier == DialectSupportTier.READ_ONLY) {
      capabilities.forEach((operation, capability) -> {
        boolean metadataOnly = operation == DialectOperation.IDENTIFIER_RULES
            || operation == DialectOperation.TYPE_MAPPING;
        if (!metadataOnly
            && capability.supportLevel() != CapabilitySupportLevel.UNSUPPORTED) {
          throw new IllegalArgumentException(
              "A READ_ONLY dialect cannot support mutation operation " + operation
          );
        }
      });
    }
  }

  /** Returns a supported capability or rejects it before any SQL is generated. */
  public DialectCapability requireSupported(final DialectOperation operation) {
    Objects.requireNonNull(operation, "operation");
    DialectCapability capability = capabilities.get(operation);
    if (capability == null) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_CAPABILITY_UNKNOWN,
          "Dialect " + dialectId + " does not declare " + operation
      );
    }
    if (capability.supportLevel() == CapabilitySupportLevel.UNSUPPORTED) {
      throw new DnaRuntimeException(
          DnaRuntimeErrorCode.DIALECT_OPERATION_UNSUPPORTED,
          "Dialect " + dialectId + " does not support " + operation
      );
    }
    return capability;
  }
}
