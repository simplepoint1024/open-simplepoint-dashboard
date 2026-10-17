package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.service.schema.PhysicalSchemaTarget;
import org.simplepoint.plugin.dna.runtime.service.schema.RecordWriteFenceProvisioner;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;
import org.springframework.stereotype.Component;

/** Authorized Schema publication prepares admission before a model becomes writable. */
@Component
public class RecordWriteFencePreparation {

  private final RecordWriteFenceProvisioner provisioner;
  private final RuntimeCalciteRecordBackend backend;

  /** Separates namespace DDL from the model's transactional initialization. */
  public RecordWriteFencePreparation(final RecordWriteFenceProvisioner provisioner, final RuntimeCalciteRecordBackend backend) {
    this.provisioner = provisioner;
    this.backend = backend;
  }

  /** Also runs on replay of an active Schema publication to prepare existing installations. */
  public void prepare(final RuntimeActor actor, final PhysicalSchemaTarget target) {
    provisioner.prepare(target);
    backend.initializeWriteFence(actor, target);
  }
}
