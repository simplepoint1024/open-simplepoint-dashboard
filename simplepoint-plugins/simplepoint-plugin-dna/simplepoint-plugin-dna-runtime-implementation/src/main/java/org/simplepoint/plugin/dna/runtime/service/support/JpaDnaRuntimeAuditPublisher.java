package org.simplepoint.plugin.dna.runtime.service.support;

import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditEvent;
import org.simplepoint.plugin.dna.runtime.api.audit.DnaRuntimeAuditPublisher;
import org.simplepoint.plugin.dna.runtime.entity.DnaRuntimeAuditEventEntity;
import org.simplepoint.plugin.dna.runtime.repository.DnaRuntimeAuditEventRepository;
import org.springframework.stereotype.Component;

/** Stores sanitized audit events in the same transaction as control-plane changes. */
@Component
public class JpaDnaRuntimeAuditPublisher implements DnaRuntimeAuditPublisher {

  private final DnaRuntimeAuditEventRepository repository;

  /** Creates an audit publisher using the control-plane repository. */
  public JpaDnaRuntimeAuditPublisher(final DnaRuntimeAuditEventRepository repository) {
    this.repository = repository;
  }

  @Override
  public void publish(final DnaRuntimeAuditEvent event) {
    DnaRuntimeAuditEventEntity entity = new DnaRuntimeAuditEventEntity();
    entity.setId(event.eventId());
    entity.setTenantId(event.tenantId());
    entity.setEventType(event.eventType());
    entity.setOccurredAt(event.occurredAt());
    entity.setActorId(event.actorId());
    entity.setPhysicalModelId(event.physicalModelId());
    entity.setAppId(event.appId());
    entity.setFormId(event.formId());
    entity.setDataSourceId(event.dataSourceId());
    entity.setModelVersion(event.modelVersion());
    entity.setAffectedRows(event.affectedRows());
    entity.setDurationMillis(event.durationMillis());
    entity.setOutcome(event.outcome());
    entity.setErrorCode(event.errorCode());
    entity.setCreatedBy(event.actorId());
    entity.setUpdatedBy(event.actorId());
    repository.save(entity);
  }
}
