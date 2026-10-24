package org.simplepoint.composition.fixture.api;

import org.simplepoint.remoting.RemoteContract;

/** Test contract shared by local and external fixture modules. */
@RemoteContract(name = "greeting")
public interface Greeting {
  /** Returns a greeting used to verify routed invocation. */
  String greet(String name);
}
