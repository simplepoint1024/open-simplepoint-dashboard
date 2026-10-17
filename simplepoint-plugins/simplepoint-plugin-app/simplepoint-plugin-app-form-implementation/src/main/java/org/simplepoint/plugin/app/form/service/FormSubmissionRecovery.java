package org.simplepoint.plugin.app.form.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded recovery driven by durable leases; safe with multiple APP replicas and after restart. */
@Component
@ConditionalOnProperty(
    prefix = "simplepoint.app.form.submission",
    name = "recovery-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class FormSubmissionRecovery {
  private static final Logger LOG = LoggerFactory.getLogger(FormSubmissionRecovery.class);
  private final FormSubmissionJournal journal;
  private final FormSubmissionProcessor processor;

  /** Creates the coordinator with explicit persistence, policy and service dependencies. */
  public FormSubmissionRecovery(FormSubmissionJournal journal, FormSubmissionProcessor processor) {
    this.journal = journal;
    this.processor = processor;
  }

  /** Resumes a bounded batch of due intents without holding a batch transaction. */
  @Scheduled(
      initialDelayString = "${simplepoint.app.form.submission.recovery-initial-delay:15000}",
      fixedDelayString = "${simplepoint.app.form.submission.recovery-delay:5000}")
  public void recover() {
    for (String id : journal.recoverable(20)) {
      try {
        processor.advance(id);
      } catch (RuntimeException failure) {
        // Never log business values, accepted authority, credentials or remote response bodies.
        LOG.warn(
            "Submission recovery deferred for {} ({})", id, failure.getClass().getSimpleName());
      }
    }
  }
}
