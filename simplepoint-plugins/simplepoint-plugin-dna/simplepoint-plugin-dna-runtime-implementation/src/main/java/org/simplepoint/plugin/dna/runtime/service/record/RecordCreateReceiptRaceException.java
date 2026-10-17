package org.simplepoint.plugin.dna.runtime.service.record;

/** Signals that another request won the unique receipt insert race. */
final class RecordCreateReceiptRaceException extends RuntimeException {

  RecordCreateReceiptRaceException(final Throwable cause) {
    super(cause);
  }
}
