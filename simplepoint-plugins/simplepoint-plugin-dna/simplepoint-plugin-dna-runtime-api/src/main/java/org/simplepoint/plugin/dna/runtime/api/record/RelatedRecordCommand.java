package org.simplepoint.plugin.dna.runtime.api.record;

import java.util.Objects;

/** Separate operation contract prevents older providers from silently ignoring a relation scope. */
public record RelatedRecordCommand(OwnedRecordCommand command, RelatedRecordScope scope) {
  /** The ordinary aggregate and its origin are one durable intent. */
  public RelatedRecordCommand {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(scope, "scope");
    if (scope.mode() == RelatedRecordScope.Mode.UNLINK
        && !(command.parent().mutations().getFirst() instanceof RecordUpdateMutation)) {
      throw new IllegalArgumentException("Unlink requires an aggregate update");
    }
  }
}
