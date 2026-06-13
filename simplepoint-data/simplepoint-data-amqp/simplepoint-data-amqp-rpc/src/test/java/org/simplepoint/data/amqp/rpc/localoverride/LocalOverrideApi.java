package org.simplepoint.data.amqp.rpc.localoverride;

import org.simplepoint.remoting.RemoteContract;

/**
 * Remote client contract used to verify local-bean override behavior.
 */
@RemoteContract(name = "sample")
public interface LocalOverrideApi {

  /**
   * Returns a simple probe value.
   *
   * @return the probe value
   */
  String ping();
}
