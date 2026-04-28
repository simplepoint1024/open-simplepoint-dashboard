package org.simplepoint.plugin.dna.federation.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.plugin.dna.federation.api.entity.FederationQueryAudit;
import org.simplepoint.plugin.dna.federation.api.service.FederationQueryAuditService;

@ExtendWith(MockitoExtension.class)
class FederationSqlAuditorTest {

  @Mock
  private FederationQueryAuditService auditService;

  @InjectMocks
  private FederationSqlAuditor auditor;

  // ----- persist() -----

  @Test
  void persistShouldCreateAuditWithCorrectFields() {
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(inv -> inv.getArgument(0));

    auditor.persist("ds1", "SELECT 1", "SUCCESS", 42L, 5L, "summary", null);

    ArgumentCaptor<FederationQueryAudit> captor = ArgumentCaptor.forClass(FederationQueryAudit.class);
    verify(auditService).create(captor.capture());
    FederationQueryAudit audit = captor.getValue();
    assertEquals("ds1", audit.getCatalogCode());
    assertEquals("SUCCESS", audit.getStatus());
    assertEquals(42L, audit.getExecutionTimeMs());
    assertEquals(5L, audit.getResultRows());
    assertEquals("SELECT 1", audit.getQueryText());
    assertEquals("summary", audit.getPushdownSummary());
    assertNull(audit.getErrorMessage());
    assertNotNull(audit.getExecutedAt());
  }

  @Test
  void persistShouldTruncateLongSqlText() {
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(inv -> inv.getArgument(0));
    String longSql = "x".repeat(FederationSqlAuditor.SQL_TEXT_MAX_LENGTH + 100);

    auditor.persist("ds1", longSql, "SUCCESS", 0L, null, null, null);

    ArgumentCaptor<FederationQueryAudit> captor = ArgumentCaptor.forClass(FederationQueryAudit.class);
    verify(auditService).create(captor.capture());
    assertEquals(FederationSqlAuditor.SQL_TEXT_MAX_LENGTH, captor.getValue().getQueryText().length());
  }

  @Test
  void persistShouldTruncateLongErrorMessage() {
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(inv -> inv.getArgument(0));
    String longError = "e".repeat(FederationSqlAuditor.AUDIT_MESSAGE_MAX_LENGTH + 50);

    auditor.persist("ds1", "SELECT 1", "FAILED", 0L, null, null, longError);

    ArgumentCaptor<FederationQueryAudit> captor = ArgumentCaptor.forClass(FederationQueryAudit.class);
    verify(auditService).create(captor.capture());
    assertEquals(FederationSqlAuditor.AUDIT_MESSAGE_MAX_LENGTH, captor.getValue().getErrorMessage().length());
  }

  @Test
  void persistShouldAcceptNullResultRows() {
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(inv -> inv.getArgument(0));

    auditor.persist("ds1", "DELETE FROM t", "SUCCESS", 10L, null, null, null);

    ArgumentCaptor<FederationQueryAudit> captor = ArgumentCaptor.forClass(FederationQueryAudit.class);
    verify(auditService).create(captor.capture());
    assertNull(captor.getValue().getResultRows());
  }

  // ----- recordFailure() -----

  @Test
  void recordFailureShouldReturnOriginalExceptionAndWriteAudit() {
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(inv -> inv.getArgument(0));
    RuntimeException original = new IllegalArgumentException("bad input");

    RuntimeException returned = auditor.recordFailure(original, "ds1", "SELECT bad", 0L, "REJECTED", null);

    assertEquals(original, returned);
    ArgumentCaptor<FederationQueryAudit> captor = ArgumentCaptor.forClass(FederationQueryAudit.class);
    verify(auditService).create(captor.capture());
    assertEquals("REJECTED", captor.getValue().getStatus());
    assertEquals("bad input", captor.getValue().getErrorMessage());
  }

  @Test
  void recordFailureShouldCombineExceptionsWhenAuditWriteFails() {
    doThrow(new RuntimeException("DB down")).when(auditService).create(any(FederationQueryAudit.class));
    RuntimeException original = new IllegalStateException("query failed");

    RuntimeException returned = auditor.recordFailure(original, "ds1", "SELECT 1", 0L, "FAILED", null);

    assertTrue(returned instanceof IllegalStateException);
    assertTrue(returned.getMessage().contains("查询审计写入失败"));
    assertEquals(original, returned.getCause());
    assertEquals(1, returned.getSuppressed().length);
  }

  @Test
  void recordFailureShouldPreservePushdownSummaryInAudit() {
    when(auditService.create(any(FederationQueryAudit.class))).thenAnswer(inv -> inv.getArgument(0));
    RuntimeException ex = new RuntimeException("timeout");

    auditor.recordFailure(ex, "ds1", "SELECT 1", 0L, "FAILED", "命中数据源: ds1");

    ArgumentCaptor<FederationQueryAudit> captor = ArgumentCaptor.forClass(FederationQueryAudit.class);
    verify(auditService).create(captor.capture());
    assertEquals("命中数据源: ds1", captor.getValue().getPushdownSummary());
  }

  // ----- resolveMessage() -----

  @Test
  void resolveMessageShouldReturnInnermostCauseMessage() {
    RuntimeException root = new RuntimeException("root cause");
    RuntimeException wrapper = new RuntimeException("wrapper", root);
    RuntimeException outer = new RuntimeException("outer", wrapper);

    assertEquals("root cause", FederationSqlAuditor.resolveMessage(outer));
  }

  @Test
  void resolveMessageShouldReturnClassNameWhenMessageIsNull() {
    RuntimeException ex = new NullPointerException();

    String msg = FederationSqlAuditor.resolveMessage(ex);

    assertEquals("NullPointerException", msg);
  }

  // ----- toElapsedMs() -----

  @Test
  void toElapsedMsShouldReturnNonNegativeValue() {
    long before = System.nanoTime();
    long elapsed = FederationSqlAuditor.toElapsedMs(before);
    assertTrue(elapsed >= 0, "elapsed should be non-negative, got " + elapsed);
  }
}
