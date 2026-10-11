package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;

/** Authorized optimistic mutations for one record. */
public interface RecordCommandService {

  /** Creates one record and returns its server-owned identity and version. */
  RecordCommandResult create(RecordCreateCommand command);

  /** Updates one record when its current version matches. */
  RecordCommandResult update(RecordUpdateCommand command);

  /** Soft-deletes one record when its current version matches. */
  RecordCommandResult delete(RecordDeleteCommand command);
}
