package org.simplepoint.plugin.dna.federation.service.impl;

import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.requireValue;
import static org.simplepoint.plugin.dna.federation.service.support.FederationServiceSupport.trimToNull;

import java.time.Instant;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Records federation query audit entries on success and failure.
 */
class FederationSqlAuditor {

  static final int SQL_TEXT_MAX_LENGTH = 12_000;

  static final int AUDIT_MESSAGE_MAX_LENGTH = 4_000;

  private final FederationQueryAuditService auditService;

  FederationSqlAuditor(final FederationQueryAuditService auditService) {
    this.auditService = auditService;
  }

  /**
   * Records a query failure audit entry and returns the exception to be (re-)thrown.
   * If the audit write itself fails, a combined exception is returned with the audit failure
   * added as a suppressed cause.
   */
  RuntimeException recordFailure(
      final RuntimeException exception,
      final String catalogCode,
      final String sql,
      final long startedAt,
      final String status,
      final String pushdownSummary
  ) {
    try {
      persist(catalogCode, sql, status, toElapsedMs(startedAt), null, pushdownSummary, resolveMessage(exception));
      return exception;
    } catch (RuntimeException auditException) {
      IllegalStateException combined = new IllegalStateException(
          resolveMessage(exception) + "；同时查询审计写入失败: " + resolveMessage(auditException),
          exception
      );
      combined.addSuppressed(auditException);
      return combined;
    }
  }

  void persist(
      final String catalogCode,
      final String sql,
      final String status,
      final long durationMs,
      final Long resultRows,
      final String pushdownSummary,
      final String errorMessage
  ) {
    FederationQueryAudit audit = new FederationQueryAudit();
    audit.setCatalogCode(trimToNull(catalogCode));
    audit.setStatus(requireValue(status, "审计状态不能为空"));
    audit.setExecutedAt(Instant.now());
    audit.setExecutionTimeMs(durationMs);
    audit.setResultRows(resultRows);
    audit.setExecutedBy(resolveExecutedBy());
    audit.setQueryText(truncate(sql, SQL_TEXT_MAX_LENGTH));
    audit.setPushdownSummary(truncate(pushdownSummary, AUDIT_MESSAGE_MAX_LENGTH));
    audit.setErrorMessage(truncate(errorMessage, AUDIT_MESSAGE_MAX_LENGTH));
    auditService.create(audit);
  }

  static long toElapsedMs(final long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  static String resolveMessage(final Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    String message = trimToNull(current.getMessage());
    return message == null ? current.getClass().getSimpleName() : message;
  }

  private static String truncate(final String value, final int maxLength) {
    String normalized = trimToNull(value);
    if (normalized == null || normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength);
  }

  private static String resolveExecutedBy() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      return "anonymous";
    }
    String username = trimToNull(authentication.getName());
    return username == null ? "anonymous" : username;
  }
}
