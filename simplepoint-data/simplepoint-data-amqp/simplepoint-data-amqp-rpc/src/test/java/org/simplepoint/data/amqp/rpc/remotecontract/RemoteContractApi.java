package org.simplepoint.data.amqp.rpc.remotecontract;

import org.simplepoint.remoting.RemoteContract;

/**
 * Remote Contract Api.
 */
@RemoteContract(name = "sample.contract")
public interface RemoteContractApi {

  /**
   * Ping.
   */
  String ping();
}
