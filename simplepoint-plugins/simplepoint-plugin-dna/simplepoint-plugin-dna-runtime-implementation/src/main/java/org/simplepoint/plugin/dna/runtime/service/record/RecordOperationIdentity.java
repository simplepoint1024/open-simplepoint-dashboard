package org.simplepoint.plugin.dna.runtime.service.record;

import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.service.support.RuntimeRequestContext.RuntimeActor;

/** Trusted request identity; namespace ownership is checked again when reading a committed receipt. */
record RecordOperationIdentity(String id, String tenantId, String actorId, String modelId,
    String key, String fingerprint) {
  static RecordOperationIdentity of(RuntimeActor actor, RecordTransactionCommand command, String fingerprint) {
    return new RecordOperationIdentity(RecordRequestFingerprint.deterministicOperationId(
        actor.tenantId(), command.physicalModelId(), command.idempotencyKey()),
        actor.tenantId(), actor.actorId(), command.physicalModelId(), command.idempotencyKey(), fingerprint);
  }
}
