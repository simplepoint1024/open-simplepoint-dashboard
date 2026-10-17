package org.simplepoint.plugin.dna.runtime.api.error;

import org.simplepoint.remoting.RemoteErrorDecoder;

/** Restores only the stable business errors declared by the DNA runtime contract. */
public final class DnaRuntimeErrorDecoder implements RemoteErrorDecoder {

  private static final String PREFIX = "DNA_RUNTIME:";

  @Override
  public DnaRuntimeException decode(final String code, final String message) {
    if (code == null || !code.startsWith(PREFIX)) {
      return null;
    }
    try {
      return new DnaRuntimeException(DnaRuntimeErrorCode.valueOf(code.substring(PREFIX.length())), message);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
