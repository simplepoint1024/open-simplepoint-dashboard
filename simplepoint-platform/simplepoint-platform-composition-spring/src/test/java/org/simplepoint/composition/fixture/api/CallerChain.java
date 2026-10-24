package org.simplepoint.composition.fixture.api;

import java.util.List;
import org.simplepoint.remoting.RemoteContract;

/** Cross-module invocation fixture. */
@RemoteContract(name = "fixture.chain")
public interface CallerChain {
  /** Returns incoming, nested and restored caller identities. */
  List<String> callers();
}
