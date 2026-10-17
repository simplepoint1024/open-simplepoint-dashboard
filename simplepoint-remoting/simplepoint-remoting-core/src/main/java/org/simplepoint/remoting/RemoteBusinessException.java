package org.simplepoint.remoting;

/** A declared business rejection whose stable code may cross a service boundary. */
public abstract class RemoteBusinessException extends RuntimeException {

  /** Creates a rejection with a client-safe message. */
  protected RemoteBusinessException(final String message) {
    super(message);
  }

  /** Retains an internal cause without transmitting it to a remote consumer. */
  protected RemoteBusinessException(final String message, final Throwable cause) {
    super(message, cause);
  }

  /** Returns the namespaced wire code owned by the service contract. */
  public abstract String remoteErrorCode();
}
