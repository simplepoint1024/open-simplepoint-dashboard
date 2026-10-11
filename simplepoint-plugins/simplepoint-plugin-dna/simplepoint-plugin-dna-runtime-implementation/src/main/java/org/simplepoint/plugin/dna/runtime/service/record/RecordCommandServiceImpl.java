package org.simplepoint.plugin.dna.runtime.service.record;

import java.util.List;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCommandResult;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateCommand;
import org.simplepoint.plugin.dna.runtime.api.record.RecordCreateMutation;
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

  private final RecordTransactionService transactionService;

  /** Creates the command facade. */
  public RecordCommandServiceImpl(final RecordTransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @Override
  public RecordCommandResult create(final RecordCreateCommand command) {
    return execute(new RecordTransactionCommand(
        command.physicalModelId(),
        List.of(new RecordCreateMutation(REFERENCE, command.values())),
        TIMEOUT_MS
    ));
  }

  @Override
  public RecordCommandResult update(final RecordUpdateCommand command) {
    return execute(new RecordTransactionCommand(
        command.physicalModelId(),
        List.of(new RecordUpdateMutation(
            REFERENCE,
            command.recordId(),
            command.expectedVersion(),
            command.values()
        )),
        TIMEOUT_MS
    ));
  }

  @Override
  public RecordCommandResult delete(final RecordDeleteCommand command) {
    return execute(new RecordTransactionCommand(
        command.physicalModelId(),
        List.of(new RecordDeleteMutation(
            REFERENCE,
            command.recordId(),
            command.expectedVersion()
        )),
        TIMEOUT_MS
    ));
  }

  private RecordCommandResult execute(final RecordTransactionCommand command) {
    return transactionService.execute(command).results().getFirst().result();
  }
}
