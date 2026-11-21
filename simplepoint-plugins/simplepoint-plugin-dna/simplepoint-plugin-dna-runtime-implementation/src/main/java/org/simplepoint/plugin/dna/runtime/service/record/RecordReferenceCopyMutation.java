package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.simplepoint.plugin.dna.runtime.api.record.RecordMutationType;
import org.simplepoint.plugin.dna.runtime.api.record.RecordReferenceCopyOrigin;

/** Protected ledger effect bound to a business mutation, never counted as a business result. */
record RecordReferenceCopyMutation(
    RecordReferenceCopyStore.Key key,
    RecordReferenceCopyStore.Stored previous,
    long sourceVersion,
    List<RecordReferenceCopyOrigin> origins,
    List<RecordDerivedCopyOrigin> derivedOrigins) {
  RecordReferenceCopyMutation(
      RecordReferenceCopyStore.Key key,
      RecordReferenceCopyStore.Stored previous,
      long sourceVersion,
      List<RecordReferenceCopyOrigin> origins) {
    this(
        key,
        previous,
        sourceVersion,
        origins,
        previous == null ? List.of() : previous.derivedOrigins());
  }

  RecordReferenceCopyMutation {
    Objects.requireNonNull(key, "key");
    var checked = new RecordReferenceCopyStore.Stored(sourceVersion, origins, derivedOrigins);
    origins = checked.origins();
    derivedOrigins = checked.derivedOrigins();
    if (previous != null && previous.sourceVersion() >= sourceVersion) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  void verify(List<RecordSqlStatement> statements) {
    var last =
        statements.reversed().stream()
            .filter(
                statement ->
                    statement.subject() != null
                        && statement.subject().tenantId().equals(key.tenantId())
                        && statement.subject().modelId().equals(key.modelId())
                        && statement.recordId().equals(key.recordId()))
            .findFirst()
            .orElse(null);
    long matches =
        statements.stream()
            .filter(
                statement ->
                    statement.subject() != null
                        && statement.subject().tenantId().equals(key.tenantId())
                        && statement.subject().modelId().equals(key.modelId())
                        && statement.recordId().equals(key.recordId())
                        && statement.resultVersion() == sourceVersion
                        && statement.mutationType() != RecordMutationType.DELETE)
            .count();
    if (matches != 1
        || last == null
        || last.resultVersion() != sourceVersion
        || last.mutationType() == RecordMutationType.DELETE) {
      throw RecordReferenceAdmission.incompatible();
    }
  }

  RecordSqlStatement statement(RecordOperationIdentity operation, Instant at) {
    if (operation == null || !key.tenantId().equals(operation.tenantId())) {
      throw RecordReferenceAdmission.incompatible();
    }
    return new RecordReferenceCopyStore()
        .write(key, operation.actorId(), previous, sourceVersion, origins, derivedOrigins, at);
  }

  /** Unkeyed commands use only the actor attached by the trusted business SQL compiler. */
  RecordSqlStatement statement(
      RecordOperationIdentity operation, List<RecordSqlStatement> business, Instant at) {
    verify(business);
    var subject =
        business.stream()
            .filter(
                statement ->
                    statement.subject() != null
                        && statement.subject().tenantId().equals(key.tenantId())
                        && statement.subject().modelId().equals(key.modelId())
                        && statement.recordId().equals(key.recordId())
                        && statement.resultVersion() == sourceVersion)
            .findFirst()
            .orElseThrow(RecordReferenceAdmission::incompatible)
            .subject();
    if (operation != null) {
      if (subject.actorId() != null && !subject.actorId().equals(operation.actorId())) {
        throw RecordReferenceAdmission.incompatible();
      }
      return statement(operation, at);
    }
    if (subject.actorId() == null) {
      throw RecordReferenceAdmission.incompatible();
    }
    return new RecordReferenceCopyStore()
        .write(key, subject.actorId(), previous, sourceVersion, origins, derivedOrigins, at);
  }
}
