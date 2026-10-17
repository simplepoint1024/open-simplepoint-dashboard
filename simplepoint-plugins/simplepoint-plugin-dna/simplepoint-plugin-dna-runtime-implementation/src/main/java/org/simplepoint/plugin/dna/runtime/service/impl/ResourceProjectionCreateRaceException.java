package org.simplepoint.plugin.dna.runtime.service.impl;

/** Forces the first-insert loser to retry after its failed transaction has rolled back. */
final class ResourceProjectionCreateRaceException extends RuntimeException {
  ResourceProjectionCreateRaceException(Throwable cause) {
    super("Resource projection creation requires a fresh transaction", cause);
  }
}
