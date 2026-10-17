package org.simplepoint.plugin.dna.runtime.service.remote;

import org.simplepoint.plugin.dna.runtime.api.context.DnaInvocationContext;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordCommand;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordQuery;
import org.simplepoint.plugin.dna.runtime.api.record.OwnedRecordView;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.service.DnaOwnedRecordService;
import org.simplepoint.plugin.dna.runtime.service.record.OwnedRecordService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/**
 * Owned aggregate transport keeps the same caller allowlist and effective user policy as ordinary
 * records.
 */
@Service
@RemoteProvider
public class DnaOwnedRecordServiceImpl implements DnaOwnedRecordService {
  private final DnaInvocationExecutor invocations;
  private final OwnedRecordService records;

  /**
   * Creates the owned-record boundary with explicit authorization and transaction collaborators.
   */
  public DnaOwnedRecordServiceImpl(DnaFormRuntimeCallerGuard guard, OwnedRecordService records) {
    this.invocations = new DnaInvocationExecutor(guard);
    this.records = records;
  }

  @Override
  public OwnedRecordView get(DnaInvocationContext context, OwnedRecordQuery query) {
    return invocations.invoke(context, true, () -> records.get(query));
  }

  @Override
  public RecordCommandResult findResult(DnaInvocationContext context, OwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.findResult(command));
  }

  @Override
  public OwnedRecordView snapshot(DnaInvocationContext context, OwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.snapshot(command));
  }

  @Override
  public RecordCommandResult execute(DnaInvocationContext context, OwnedRecordCommand command) {
    return invocations.invoke(context, true, () -> records.execute(command));
  }
}
