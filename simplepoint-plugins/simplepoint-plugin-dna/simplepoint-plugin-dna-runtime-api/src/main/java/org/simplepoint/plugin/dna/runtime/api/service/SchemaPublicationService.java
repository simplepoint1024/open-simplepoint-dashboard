package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationCommand;
import org.simplepoint.plugin.dna.runtime.api.migration.SchemaPublicationView;

/** Durable and idempotent schema publication entry point. */
public interface SchemaPublicationService {

  /** Publishes one immutable target version or returns its existing migration. */
  SchemaPublicationView publish(String physicalModelId, SchemaPublicationCommand command);

  /** Reads a tenant-scoped publication without exposing its native SQL. */
  SchemaPublicationView get(String migrationId);

  /** Reads the idempotent publication record for one model version. */
  SchemaPublicationView getByTargetVersion(String physicalModelId, int targetVersion);

  /** Reclaims and retries a failed publication after checking the model version. */
  SchemaPublicationView retry(String migrationId, long expectedLockVersion);

  /** Fences and fails a stale nonterminal publication so an operator can retry it safely. */
  SchemaPublicationView recover(String migrationId, long expectedLockVersion);
}
