package org.simplepoint.plugin.dna.runtime.api.error;

/** Stable language-neutral diagnostics exposed by DNA Runtime services. */
public enum DnaRuntimeErrorCode {
  INVALID_ARGUMENT(DnaRuntimeErrorCategory.VALIDATION),
  SCHEMA_INVALID(DnaRuntimeErrorCategory.VALIDATION),
  VALUE_CONVERSION_FAILED(DnaRuntimeErrorCategory.VALIDATION),
  TENANT_CONTEXT_REQUIRED(DnaRuntimeErrorCategory.AUTHORIZATION),
  ACTOR_CONTEXT_REQUIRED(DnaRuntimeErrorCategory.AUTHORIZATION),
  PHYSICAL_MODEL_NOT_FOUND(DnaRuntimeErrorCategory.NOT_FOUND),
  PHYSICAL_MODEL_ALREADY_EXISTS(DnaRuntimeErrorCategory.CONFLICT),
  PHYSICAL_MODEL_REGISTRATION_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  PHYSICAL_BINDING_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  RESOURCE_PROJECTION_SUPERSEDED(DnaRuntimeErrorCategory.CONFLICT),
  PHYSICAL_MODEL_VERSION_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  PHYSICAL_MODEL_STATE_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  PLACEMENT_UNAVAILABLE(DnaRuntimeErrorCategory.CAPABILITY),
  DIALECT_CAPABILITY_UNKNOWN(DnaRuntimeErrorCategory.CAPABILITY),
  DIALECT_OPERATION_UNSUPPORTED(DnaRuntimeErrorCategory.CAPABILITY),
  DATA_SOURCE_UNAVAILABLE(DnaRuntimeErrorCategory.CAPABILITY),
  SCHEMA_MIGRATION_NOT_FOUND(DnaRuntimeErrorCategory.NOT_FOUND),
  SCHEMA_MIGRATION_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  SCHEMA_DRIFT_DETECTED(DnaRuntimeErrorCategory.CONFLICT),
  SCHEMA_PRECHECK_FAILED(DnaRuntimeErrorCategory.CONFLICT),
  SCHEMA_DESTRUCTIVE_CHANGE_REQUIRES_APPROVAL(DnaRuntimeErrorCategory.CONFLICT),
  SCHEMA_PUBLICATION_INTERRUPTED(DnaRuntimeErrorCategory.INTERNAL),
  SCHEMA_PUBLICATION_FAILED(DnaRuntimeErrorCategory.INTERNAL),
  SCHEMA_VERIFICATION_FAILED(DnaRuntimeErrorCategory.INTERNAL),
  METADATA_INVALIDATION_FAILED(DnaRuntimeErrorCategory.INTERNAL),
  PHYSICAL_MODEL_NOT_ACTIVE(DnaRuntimeErrorCategory.CONFLICT),
  RECORD_ACCESS_DENIED(DnaRuntimeErrorCategory.AUTHORIZATION),
  RECORD_QUERY_INVALID(DnaRuntimeErrorCategory.VALIDATION),
  RECORD_COMMAND_INVALID(DnaRuntimeErrorCategory.VALIDATION),
  RECORD_SCHEMA_INCOMPATIBLE(DnaRuntimeErrorCategory.CONFLICT),
  RECORD_IDEMPOTENCY_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  RECORD_NOT_FOUND(DnaRuntimeErrorCategory.NOT_FOUND),
  RECORD_VERSION_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  RECORD_WRITE_POLICY_CONFLICT(DnaRuntimeErrorCategory.CONFLICT),
  RECORD_WRITE_POLICY_PRECHECK_FAILED(DnaRuntimeErrorCategory.CONFLICT),
  RECORD_EXECUTION_FAILED(DnaRuntimeErrorCategory.INTERNAL),
  RECORD_TRANSACTION_FAILED(DnaRuntimeErrorCategory.INTERNAL),
  RECORD_RATE_LIMITED(DnaRuntimeErrorCategory.CAPABILITY),
  SCHEMA_SERIALIZATION_FAILED(DnaRuntimeErrorCategory.INTERNAL),
  PERSISTENCE_FAILED(DnaRuntimeErrorCategory.INTERNAL);

  private final DnaRuntimeErrorCategory category;

  DnaRuntimeErrorCode(final DnaRuntimeErrorCategory category) {
    this.category = category;
  }

  /** Returns the transport-independent error category. */
  public DnaRuntimeErrorCategory category() {
    return category;
  }
}
