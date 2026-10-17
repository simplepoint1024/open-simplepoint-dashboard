package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordDeleteMutation;
import org.simplepoint.plugin.dna.runtime.api.record.RecordTransactionCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordUpdateMutation;
import org.simplepoint.plugin.dna.runtime.api.service.RecordCommandService;
import org.simplepoint.plugin.dna.runtime.api.service.RecordTransactionService;
import org.springframework.stereotype.Service;

/** Single-record command facade over the atomic transaction contract. */
@Service
public class RecordCommandServiceImpl implements RecordCommandService {

  private static final String REFERENCE = "command";
  private static final int TIMEOUT_MS = 30_000;

  private final RecordCreateCoordinator createCoordinator;
  private final RecordTransactionService transactionService;

  /** Creates the command facade. */
  public RecordCommandServiceImpl(
      final RecordCreateCoordinator createCoordinator,
      final RecordTransactionService transactionService
  ) {
    this.createCoordinator = createCoordinator;
    this.transactionService = transactionService;
  }

  @Override
  public RecordCommandResult create(final RecordCreateCommand command) {
    return createCoordinator.create(command);
  }

  @Override
  public RecordCommandResult findCreateResult(final RecordCreateCommand command) {
    return createCoordinator.findResult(command);
  }

  @Override
  public RecordCommandResult findUpdateResult(final RecordUpdateCommand command) {
    var result = transactionService.findResult(updateTransaction(command));
    return result == null ? null : result.results().getFirst().result();
  }

  @Override
  public RecordCommandResult update(final RecordUpdateCommand command) {
    return execute(updateTransaction(command));
  }

  private static RecordTransactionCommand updateTransaction(final RecordUpdateCommand command) {
    return new RecordTransactionCommand(command.physicalModelId(), command.schemaReference(),
        List.of(new RecordUpdateMutation(REFERENCE, command.recordId(), command.expectedVersion(), command.values())),
        TIMEOUT_MS, command.idempotencyKey(), command.writePolicyVersion());
  }

  @Override
  public RecordCommandResult delete(final RecordDeleteCommand command) {
    return execute(new RecordTransactionCommand(
        command.physicalModelId(),
        command.schemaReference(),
        List.of(new RecordDeleteMutation(
            REFERENCE,
            command.recordId(),
            command.expectedVersion()
        )),
        TIMEOUT_MS,
        command.idempotencyKey(),
        command.writePolicyVersion()
    ));
  }

  private RecordCommandResult execute(final RecordTransactionCommand command) {
    return transactionService.execute(command).results().getFirst().result();
  }
}
