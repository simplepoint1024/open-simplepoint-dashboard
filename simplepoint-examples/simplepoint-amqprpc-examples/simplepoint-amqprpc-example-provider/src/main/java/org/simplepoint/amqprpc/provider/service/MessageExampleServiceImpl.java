package org.simplepoint.amqprpc.provider.service;

import org.simplepoint.amqprpc.api.MessageExample;
import org.simplepoint.amqprpc.api.MessageExampleService;
import org.simplepoint.data.amqp.annotation.AmqpRemoteService;

/**
 * Implementation of the MessageExampleService interface.
 */
@AmqpRemoteService
public class MessageExampleServiceImpl implements MessageExampleService {
  @Override
  public MessageExample echo(String message) {
    MessageExample messageExample = new MessageExample();
    messageExample.setMessage("Echo: " + message);
    return messageExample;
  }
}
