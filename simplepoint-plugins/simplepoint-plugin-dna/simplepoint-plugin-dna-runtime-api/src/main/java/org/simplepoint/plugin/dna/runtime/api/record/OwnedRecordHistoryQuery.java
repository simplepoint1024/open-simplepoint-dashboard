package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/**
 * Exact immutable operation selected by the trusted APP owner after workflow/record authorization.
 * The original actor identifies a receipt only; the invocation actor remains the current viewer.
 */
public record OwnedRecordHistoryQuery(
    String contractVersion,
    String operationActorId,
    String appId,
    String formId,
    String resourceCode,
    String recordId,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) long recordVersion,
    OwnedRecordCommand command,
    RelatedRecordScope relatedScope,
    MappedOwnedRecordCommand mapped,
    @com.fasterxml.jackson.annotation.JsonProperty(required = true) int timeoutMs) {
  /** Uses the standard read budget without changing the frozen command's receipt fingerprint. */
  public OwnedRecordHistoryQuery(
      String contractVersion,
      String operationActorId,
      String appId,
      String formId,
      String resourceCode,
      String recordId,
      long recordVersion,
      OwnedRecordCommand command,
      RelatedRecordScope relatedScope,
      MappedOwnedRecordCommand mapped) {
    this(
        contractVersion,
        operationActorId,
        appId,
        formId,
        resourceCode,
        recordId,
        recordVersion,
        command,
        relatedScope,
        mapped,
        30_000);
  }

  /** This read-only contract neither accepts permission grants nor changes operation ownership. */
  public OwnedRecordHistoryQuery {
    if (!"1".equals(contractVersion)
        || operationActorId == null
        || operationActorId.isBlank()
        || operationActorId.length() > 64
        || operationActorId.indexOf('\0') >= 0
        || !operationActorId.equals(operationActorId.trim())
        || resourceCode == null
        || resourceCode.isBlank()
        || resourceCode.length() > 200
        || resourceCode.indexOf('\0') >= 0
        || !resourceCode.equals(resourceCode.trim())
        || recordVersion < 0
        || timeoutMs < 1
        || timeoutMs > 30_000) {
      throw new IllegalArgumentException("Invalid historical operation identity");
    }
    appId = RecordQueryLimits.modelId(appId);
    formId = RecordQueryLimits.modelId(formId);
    recordId = RecordQueryLimits.recordId(recordId);
    Objects.requireNonNull(command, "command");
    if (command.snapshot() == null
        || command.parent().mutations().getFirst() instanceof RecordDeleteMutation
        || mapped != null
            && (!mapped.command().equals(command)
                || !Objects.equals(mapped.relatedScope(), relatedScope))) {
      throw new IllegalArgumentException("Historical access requires the exact captured operation");
    }
    if (relatedScope != null) {
      new RelatedRecordCommand(command, relatedScope);
    }
  }

  /** A discovery/value read sequence shares one deadline while preserving the original intent. */
  public OwnedRecordHistoryQuery withTimeout(int remainingMillis) {
    return new OwnedRecordHistoryQuery(
        contractVersion,
        operationActorId,
        appId,
        formId,
        resourceCode,
        recordId,
        recordVersion,
        command,
        relatedScope,
        mapped,
        remainingMillis);
  }
}
