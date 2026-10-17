package org.simplepoint.remoting;

/** Preserves generic error handling for contracts without declared business errors. */
public final class NoRemoteErrorDecoder implements RemoteErrorDecoder {

  @Override
  public RemoteBusinessException decode(final String code, final String message) {
    return null;
  }
}
