package org.simplepoint.plugin.dna.runtime.service.record;

import java.time.Instant;
import java.util.UUID;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEvent;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEventType;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditOutcome;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorCode;
import org.springframework.stereotype.Component;

/** Builds sanitized record audit events without SQL text or business values. */
@Component
class RecordAuditSupport {

  private final DnaRuntimeAuditPublisher publisher;

  RecordAuditSupport(final DnaRuntimeAuditPublisher publisher) {
    this.publisher = publisher;
  }

  void success(
      final DnaRuntimeAuditEventType type,
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final long affectedRows,
      final long durationMillis
  ) {
    publish(
        type,
        model,
        access,
        affectedRows,
        durationMillis,
        DnaRuntimeAuditOutcome.SUCCEEDED,
        null
    );
  }

  void failure(
      final DnaRuntimeAuditEventType type,
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final long durationMillis,
      final DnaRuntimeErrorCode errorCode
  ) {
    publish(
        type,
        model,
        access,
        null,
        durationMillis,
        DnaRuntimeAuditOutcome.FAILED,
        errorCode
    );
  }

  private void publish(
      final DnaRuntimeAuditEventType type,
      final RuntimeRecordModel model,
      final RecordAccessContext access,
      final Long affectedRows,
      final long durationMillis,
      final DnaRuntimeAuditOutcome outcome,
      final DnaRuntimeErrorCode errorCode
  ) {
    publisher.publish(new DnaRuntimeAuditEvent(
        UUID.randomUUID().toString(),
        type,
        Instant.now(),
        access.actor().tenantId(),
        access.actor().actorId(),
        model.physicalModelId(),
        model.appId(),
        model.formId(),
        model.target().metadataTarget().dataSourceId(),
        model.activeVersion(),
        affectedRows,
        durationMillis,
        outcome,
        errorCode
    ));
  }
}
