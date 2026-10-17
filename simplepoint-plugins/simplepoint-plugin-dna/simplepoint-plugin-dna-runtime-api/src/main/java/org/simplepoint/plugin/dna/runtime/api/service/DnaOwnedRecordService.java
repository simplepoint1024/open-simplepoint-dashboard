package org.simplepoint.plugin.dna.runtime.api.service;

import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.error.DnaRuntimeErrorDecoder;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.remoting.RemoteContract;

/** Same-datasource aggregate boundary; never permits independent child-row access. */
@RemoteContract(
    name = "dna.owned-record",
    version = "1",
    errorDecoder = DnaRuntimeErrorDecoder.class)
public interface DnaOwnedRecordService {
  /** Reads an authorized parent and its owned child collections with consistent versions. */
  OwnedRecordView get(DnaInvocationContext context, OwnedRecordQuery query);

  /** Returns the original confirmation for an exact tenant, actor and request identity. */
  RecordCommandResult findResult(DnaInvocationContext context, OwnedRecordCommand command);

  /**
   * Reads the immutable result of this exact captured operation; null means it has not committed.
   */
  OwnedRecordView snapshot(DnaInvocationContext context, OwnedRecordCommand command);

  /** Commits the parent and child mutations, receipt and optional snapshot atomically. */
  RecordCommandResult execute(DnaInvocationContext context, OwnedRecordCommand command);
}
