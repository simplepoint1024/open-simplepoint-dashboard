package org.simplepoint.plugin.ai.core.service.adapter;

import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.ai.core.api.entity.AiInvocationRecord;
import org.simplepoint.plugin.ai.core.api.entity.AiModelDefinition;
import org.simplepoint.plugin.ai.core.api.entity.AiProviderDefinition;
import org.simplepoint.plugin.ai.core.api.model.AiInvocationOperation;
import org.simplepoint.plugin.ai.core.api.model.AiInvocationStatus;
import org.simplepoint.plugin.ai.core.api.model.AiResourceScope;
import org.simplepoint.plugin.ai.core.api.repository.AiInvocationRecordRepository;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.GenerationResult;
import org.simplepoint.plugin.ai.core.api.vo.AiGenerationModels.TokenUsage;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy;
import org.simplepoint.plugin.ai.core.service.support.AiScopeAccessPolicy.ScopeAssignment;
import org.springframework.stereotype.Component;

/** Writes metadata-only invocation lifecycle records without retaining request or response content. */
@Slf4j
@Component
final class AiInvocationLedger {

  private final AiInvocationRecordRepository repository;

  private final AiScopeAccessPolicy scopeAccessPolicy;

  AiInvocationLedger(
      final AiInvocationRecordRepository repository,
      final AiScopeAccessPolicy scopeAccessPolicy
  ) {
    this.repository = repository;
    this.scopeAccessPolicy = scopeAccessPolicy;
  }

  InvocationActor captureActor() {
    AuthorizationContext context = AuthorizationContextHolder.getContext();
    ScopeAssignment scope = scopeAccessPolicy.currentManagementScope();
    return new InvocationActor(
        scope.scopeType(), scope.tenantId(),
        context == null ? null : context.getUserId(),
        context == null ? null : context.getContextId()
    );
  }

  AiInvocationRecord start(
      final String invocationId,
      final AiProviderDefinition provider,
      final AiModelDefinition model,
      final AiInvocationOperation operation,
      final boolean stream
  ) {
    return start(captureActor(), invocationId, provider, model, operation, stream);
  }

  AiInvocationRecord start(
      final InvocationActor actor,
      final String invocationId,
      final AiProviderDefinition provider,
      final AiModelDefinition model,
      final AiInvocationOperation operation,
      final boolean stream
  ) {
    try {
      AiInvocationRecord record = new AiInvocationRecord();
      record.setId(invocationId);
      record.setScopeType(actor.scopeType());
      record.setTenantId(actor.tenantId());
      record.setUserId(actor.userId());
      record.setContextId(actor.contextId());
      record.setProviderDefinitionId(provider.getId());
      record.setModelDefinitionId(model.getId());
      record.setModelId(model.getModelId());
      record.setProviderType(provider.getProviderType());
      record.setOperation(operation);
      record.setStream(stream);
      record.setStatus(AiInvocationStatus.RUNNING);
      record.setStartedAt(Instant.now());
      return repository.save(record);
    } catch (RuntimeException ex) {
      log.warn("Unable to start AI invocation ledger record {}: {}", invocationId, ex.getMessage());
      return null;
    }
  }

  void success(final AiInvocationRecord record, final GenerationResult result) {
    if (record == null || result == null) {
      return;
    }
    record.setStatus(AiInvocationStatus.SUCCEEDED);
    record.setProviderRequestId(result.providerRequestId());
    record.setCompletedAt(result.completedAt());
    record.setDurationMillis(result.durationMillis());
    applyUsage(record, result.usage());
    save(record);
  }

  void success(
      final AiInvocationRecord record,
      final TokenUsage usage,
      final long durationMillis,
      final String providerRequestId
  ) {
    if (record == null) {
      return;
    }
    record.setStatus(AiInvocationStatus.SUCCEEDED);
    record.setProviderRequestId(providerRequestId);
    record.setCompletedAt(Instant.now());
    record.setDurationMillis(durationMillis);
    applyUsage(record, usage);
    save(record);
  }

  void failure(final AiInvocationRecord record, final RuntimeException error) {
    if (record == null) {
      return;
    }
    record.setStatus(AiInvocationStatus.FAILED);
    record.setCompletedAt(Instant.now());
    record.setDurationMillis(Math.max(0L,
        record.getCompletedAt().toEpochMilli() - record.getStartedAt().toEpochMilli()));
    record.setErrorCode(error.getClass().getSimpleName());
    record.setErrorMessage("AI invocation failed; request and response content were not retained");
    save(record);
  }

  private void save(final AiInvocationRecord record) {
    try {
      repository.save(record);
    } catch (RuntimeException ex) {
      log.warn("Unable to update AI invocation ledger record {}: {}", record.getId(), ex.getMessage());
    }
  }

  private static void applyUsage(final AiInvocationRecord record, final TokenUsage usage) {
    if (usage == null) {
      return;
    }
    record.setInputTokens(usage.inputTokens());
    record.setOutputTokens(usage.outputTokens());
    record.setTotalTokens(usage.totalTokens());
    record.setCachedInputTokens(usage.cachedInputTokens());
  }

  record InvocationActor(
      AiResourceScope scopeType,
      String tenantId,
      String userId,
      String contextId
  ) {
  }

}
