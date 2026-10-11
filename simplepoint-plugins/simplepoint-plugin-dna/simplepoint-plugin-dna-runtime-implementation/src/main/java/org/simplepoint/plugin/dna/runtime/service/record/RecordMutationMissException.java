package org.simplepoint.plugin.dna.runtime.service.record;

/** Internal rollback signal for an optimistic mutation that matched no authorized row. */
final class RecordMutationMissException extends RuntimeException {

  private final int mutationIndex;

  RecordMutationMissException(final int mutationIndex) {
    super("Record mutation matched no row");
    this.mutationIndex = mutationIndex;
  }

  int mutationIndex() {
    return mutationIndex;
  }
}
