package org.simplepoint.plugin.app.form.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormErrorCode;
import org.simplepoint.plugin.app.form.api.error.ApplicationFormException;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormRecordView;
import org.simplepoint.plugin.app.form.api.model.ApplicationFormSubmissionView;
import org.simplepoint.plugin.app.form.entity.ApplicationFormEntity;
import org.simplepoint.plugin.app.form.entity.ApplicationFormSubmissionEntity;
import org.simplepoint.plugin.workflow.api.model.WorkflowModels.ProcessInstanceSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short control-plane transactions; remote DNA/Workflow calls never hold this database lock. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class FormSubmissionJournal {
  private static final Set<String> TERMINAL =
      Set.of("COMPLETED", "REJECTED", "WITHDRAWN", "CANCELED");
  private final EntityManager entityManager;
  private final FormSubmissionCodec codec;
  private final Clock clock;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormSubmissionJournal(
      EntityManager entityManager, FormSubmissionCodec codec, Clock clock) {
    this.entityManager = entityManager;
    this.codec = codec;
    this.clock = clock;
  }

  /** Serializes acceptance on the stable form identity, before any external side effect. */
  public ApplicationFormSubmissionView accept(
      String id, String fingerprint, AcceptedFormSubmission accepted) {
    var form = accepted.form();
    var owner =
        entityManager.find(
            ApplicationFormEntity.class, form.formId(), LockModeType.PESSIMISTIC_WRITE);
    if (owner == null || !owner.getTenantId().equals(form.tenantId())) {
      throw conflict("Form is unavailable");
    }
    var existing = entityManager.find(ApplicationFormSubmissionEntity.class, id);
    if (existing != null) {
      return checked(existing, form.tenantId(), accepted.dataAuthority().actorId(), fingerprint);
    }
    FormResourceLifecycleGuard.requireAvailable(entityManager, form.tenantId(), form.pageId());
    var row = new ApplicationFormSubmissionEntity();
    row.setId(id);
    row.setTenantId(form.tenantId());
    row.setApplicationId(form.applicationId());
    row.setPageId(form.pageId());
    row.setFormId(form.formId());
    row.setFormVersion(form.formVersion());
    row.setActorId(accepted.dataAuthority().actorId());
    row.setRequestFingerprint(fingerprint);
    row.setIntentJson(codec.write(accepted));
    row.setTitle(accepted.command().title());
    row.setDefinitionId(accepted.command().definitionId());
    row.setDefinitionVersion(accepted.command().definitionVersion());
    row.setPreviousSubmissionId(accepted.command().previousSubmissionId());
    row.setRecordId(accepted.command().recordId());
    row.setRecordVersion(accepted.command().expectedVersion());
    row.setStatus("ACCEPTED");
    row.setStep("PREPARED");
    row.setCreatedAt(clock.instant());
    row.setUpdatedAt(clock.instant());
    row.setNextAttemptAt(clock.instant());
    entityManager.persist(row);
    entityManager.flush();
    return view(row);
  }

  /** Finds an accepted intent only when tenant, actor and request fingerprint match. */
  public ApplicationFormSubmissionView findExact(
      String id, String tenant, String actor, String fingerprint) {
    var row = entityManager.find(ApplicationFormSubmissionEntity.class, id);
    return row == null ? null : checked(row, tenant, actor, fingerprint);
  }

  /** Returns bounded submission metadata only to its original tenant and actor. */
  public ApplicationFormSubmissionView getOwned(String id, String tenant, String actor) {
    var row = entityManager.find(ApplicationFormSubmissionEntity.class, id);
    if (row == null || !row.getTenantId().equals(tenant) || !row.getActorId().equals(actor)) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_NOT_FOUND, "Submission is unavailable");
    }
    return view(row);
  }

  /**
   * Internal loader; the caller must first establish workflow participation or original-submitter
   * ownership.
   */
  public Snapshot snapshot(String id, String tenant) {
    var row = entityManager.find(ApplicationFormSubmissionEntity.class, id);
    if (row == null || !row.getTenantId().equals(tenant) || row.getSnapshotJson() == null) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_NOT_FOUND, "Submission snapshot is unavailable");
    }
    return new Snapshot(
        view(row),
        codec.read(row.getIntentJson(), AcceptedFormSubmission.class),
        codec.read(row.getSnapshotJson(), ApplicationFormRecordView.class));
  }

  /** Claims at most one bounded operation. A crashed worker is replaced after its lease expires. */
  public Work claim(String id) {
    var row =
        entityManager.find(
            ApplicationFormSubmissionEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    Instant now = clock.instant();
    if (row == null
        || "TERMINAL".equals(row.getStep())
        || "ATTENTION".equals(row.getStatus())
        || row.getNextAttemptAt().isAfter(now)
        || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now)) {
      return null;
    }
    row.setExecutionToken(UUID.randomUUID().toString());
    row.setLeaseUntil(now.plusSeconds(90));
    row.setAttempt(row.getAttempt() + 1);
    row.setStatus("PROCESSING");
    row.setUpdatedAt(now);
    return new Work(
        row.getId(),
        row.getExecutionToken(),
        row.getStep(),
        codec.read(row.getIntentJson(), AcceptedFormSubmission.class),
        row.getSnapshotJson() == null
            ? null
            : codec.read(row.getSnapshotJson(), ApplicationFormRecordView.class),
        row.getProcessInstanceId());
  }

  /**
   * Snapshot identity is immutable even when a stale worker returns after another worker
   * progressed.
   */
  public void captured(String id, String token, ApplicationFormRecordView snapshot) {
    var row = locked(id, token);
    if (row == null) {
      return;
    }
    if (!"PREPARED".equals(row.getStep()) || snapshot == null) {
      throw conflict("Unexpected submission capture state");
    }
    row.setSnapshotJson(codec.write(snapshot));
    row.setRecordId(snapshot.id());
    row.setRecordVersion(snapshot.version());
    row.setStep("DATA_COMMITTED");
    ready(row, "ACCEPTED", clock.instant());
  }

  /** Adopts only the process identity pinned by the accepted form intent. */
  public void started(String id, String token, ProcessInstanceSummary process) {
    var row = locked(id, token);
    if (row == null) {
      return;
    }
    if (!Set.of("DATA_COMMITTED", "STARTED").contains(row.getStep())
        || !row.getDefinitionId().equals(process.definitionId())
        || row.getDefinitionVersion() != process.definitionVersion()
        || !row.getActorId().equals(process.starterId())
        || !("app-form:" + id).equals(process.businessKey())
        || row.getProcessInstanceId() != null && !row.getProcessInstanceId().equals(process.id())) {
      throw conflict("Workflow result does not belong to this submission");
    }
    if (!"RUNNING".equals(process.status()) && !TERMINAL.contains(process.status())) {
      throw conflict("Unsupported workflow result state");
    }
    row.setProcessInstanceId(process.id());
    row.setProcessStatus(process.status());
    row.setStep(TERMINAL.contains(process.status()) ? "TERMINAL" : "STARTED");
    ready(row, "SUCCEEDED", clock.instant().plusSeconds(20));
  }

  /** Uncertain network outcomes remain recoverable; no committed data or process is undone here. */
  public void failed(String id, String token, String code, boolean permanent) {
    var row = locked(id, token);
    if (row == null) {
      return;
    }
    row.setErrorCode(
        code == null ? "SUBMISSION_FAILED" : code.substring(0, Math.min(80, code.length())));
    row.setStatus(permanent || row.getAttempt() >= 10 ? "ATTENTION" : "RETRY");
    row.setExecutionToken(null);
    row.setLeaseUntil(null);
    row.setNextAttemptAt(
        clock.instant().plusSeconds(Math.min(300, 5L << Math.min(6, row.getAttempt() - 1))));
    row.setUpdatedAt(clock.instant());
  }

  /** Explicit retry keeps the same intent, data receipt and process operation key. */
  public void retry(String id, String tenant, String actor) {
    var row =
        entityManager.find(
            ApplicationFormSubmissionEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (row == null || !row.getTenantId().equals(tenant) || !row.getActorId().equals(actor)) {
      throw new ApplicationFormException(
          ApplicationFormErrorCode.RECORD_NOT_FOUND, "Submission is unavailable");
    }
    if ("TERMINAL".equals(row.getStep())
        || row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(clock.instant())) {
      return;
    }
    ready(row, "ACCEPTED", clock.instant());
  }

  /** Selects due intents whose leases expired, excluding terminal and operator-held work. */
  public List<String> recoverable(int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Invalid recovery batch size");
    }
    return entityManager
        .createQuery(
            "select submission.id from ApplicationFormSubmissionEntity submission where"
                + " submission.step <> 'TERMINAL' and submission.status <> 'ATTENTION' and"
                + " submission.nextAttemptAt <= :now and (submission.leaseUntil is null or"
                + " submission.leaseUntil <= :now) order by submission.nextAttemptAt,"
                + " submission.id",
            String.class)
        .setParameter("now", clock.instant())
        .setMaxResults(limit)
        .getResultList();
  }

  private ApplicationFormSubmissionEntity locked(String id, String token) {
    var row =
        entityManager.find(
            ApplicationFormSubmissionEntity.class, id, LockModeType.PESSIMISTIC_WRITE);
    return row == null || token == null || !token.equals(row.getExecutionToken()) ? null : row;
  }

  private void ready(ApplicationFormSubmissionEntity row, String status, Instant next) {
    row.setStatus(status);
    row.setAttempt(0);
    row.setErrorCode(null);
    row.setExecutionToken(null);
    row.setLeaseUntil(null);
    row.setNextAttemptAt(next);
    row.setUpdatedAt(clock.instant());
  }

  private static ApplicationFormSubmissionView checked(
      ApplicationFormSubmissionEntity row, String tenant, String actor, String fingerprint) {
    if (!row.getTenantId().equals(tenant)
        || !row.getActorId().equals(actor)
        || !Objects.equals(row.getRequestFingerprint(), fingerprint)) {
      throw conflict("Submission key belongs to a different intent");
    }
    return view(row);
  }

  static ApplicationFormSubmissionView view(ApplicationFormSubmissionEntity row) {
    return new ApplicationFormSubmissionView(
        row.getId(),
        row.getTitle(),
        row.getApplicationId(),
        row.getPageId(),
        row.getFormId(),
        row.getFormVersion(),
        row.getRecordId(),
        row.getRecordVersion(),
        row.getDefinitionId(),
        row.getDefinitionVersion(),
        row.getPreviousSubmissionId(),
        row.getProcessInstanceId(),
        row.getProcessStatus(),
        row.getStatus(),
        row.getStep(),
        row.getErrorCode(),
        row.getAttempt(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private static ApplicationFormException conflict(String message) {
    return new ApplicationFormException(ApplicationFormErrorCode.RECORD_CONFLICT, message);
  }

  record Snapshot(
      ApplicationFormSubmissionView view,
      AcceptedFormSubmission accepted,
      ApplicationFormRecordView record) {}

  record Work(
      String id,
      String token,
      String step,
      AcceptedFormSubmission accepted,
      ApplicationFormRecordView snapshot,
      String processInstanceId) {}
}
