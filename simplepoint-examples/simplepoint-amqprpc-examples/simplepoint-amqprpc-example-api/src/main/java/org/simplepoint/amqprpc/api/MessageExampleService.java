package org.simplepoint.amqprpc.api;

import org.simplepoint.data.amqp.annotation.AmqpRemoteClient;

/**
 * Message example service interface.
 */
@AmqpRemoteClient(to = "messages")
public interface MessageExampleService {
  /**
   * Echo the input message.
   *
   * @param message the input message
   * @return the echoed message
   */
  MessageExample echo(String message);
}
