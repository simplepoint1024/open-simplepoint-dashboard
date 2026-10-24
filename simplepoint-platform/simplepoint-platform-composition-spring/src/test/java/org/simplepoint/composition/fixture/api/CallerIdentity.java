package org.simplepoint.composition.fixture.api;

import org.simplepoint.remoting.RemoteContract;

/** Reports the verified caller at the leaf of a test invocation. */
@RemoteContract(name = "fixture.identity")
public interface CallerIdentity {
  /** Returns the signed service client identity. */
  String caller();
}
