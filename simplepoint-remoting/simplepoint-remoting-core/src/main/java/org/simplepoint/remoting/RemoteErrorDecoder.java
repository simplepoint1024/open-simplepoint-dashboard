package org.simplepoint.remoting;

/** Decodes only errors declared by a trusted, locally installed service contract. */
public interface RemoteErrorDecoder {

  /** Returns a typed rejection, or null for a code this contract does not recognize. */
  RemoteBusinessException decode(String code, String message);
}
