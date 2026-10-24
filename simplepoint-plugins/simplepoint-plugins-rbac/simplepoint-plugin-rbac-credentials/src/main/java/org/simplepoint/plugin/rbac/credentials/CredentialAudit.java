package org.simplepoint.plugin.rbac.credentials;

import java.time.Instant;
import java.util.UUID;
import org.simplepoint.events.api.EventMessage;
import org.simplepoint.events.api.EventPublisher;
import org.simplepoint.plugin.auditing.logging.api.event.CredentialAuditContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A credential outcome commits to the identity owner's outbox independently of caller rollback. */
@Service
public class CredentialAudit {
  private final EventPublisher publisher;

  /** Uses the platform event API; module assembly fixes the producer's storage and credentials. */
  public CredentialAudit(EventPublisher publisher) {
    this.publisher = publisher;
  }

  /** Returning proves local durable acceptance, not broker delivery or consumer completion. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(CredentialAuditContract.Outcome outcome) {
    UUID id = UUID.randomUUID();
    publisher.publish(
        new EventMessage(
            id,
            CredentialAuditContract.PUBLICATION.eventType(),
            CredentialAuditContract.PUBLICATION.schemaVersion(),
            CredentialAuditContract.PRODUCER,
            "platform",
            "credential-authentication",
            0,
            id.toString(),
            id.toString(),
            Instant.ofEpochMilli(outcome.occurredAt()),
            CredentialAuditContract.encode(outcome)));
  }
}
