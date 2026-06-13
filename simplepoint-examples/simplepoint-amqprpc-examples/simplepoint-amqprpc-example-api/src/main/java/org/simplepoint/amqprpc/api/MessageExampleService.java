package org.simplepoint.amqprpc.api;

import org.simplepoint.remoting.RemoteContract;

/**
 * Message example service interface.
 */
@RemoteContract(name = "messages")
public interface MessageExampleService {
  /**
   * Echo the input message.
   *
   * @param message the input message
   * @return the echoed message
   */
  MessageExample echo(String message);
}
