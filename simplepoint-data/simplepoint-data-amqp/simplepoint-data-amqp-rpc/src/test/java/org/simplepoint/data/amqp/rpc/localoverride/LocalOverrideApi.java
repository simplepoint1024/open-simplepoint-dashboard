package org.simplepoint.data.amqp.rpc.localoverride;

import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;

/**
 * Remote client contract used to verify local-bean override behavior.
 */
@AmqpRemoteClient(to = "sample")
public interface LocalOverrideApi {

  /**
   * Returns a simple probe value.
   *
   * @return the probe value
   */
  String ping();
}
