package org.simplepoint.amqprpc.provider.service;

import org.simplepoint.amqprpc.api.MessageExample;
import org.simplepoint.amqprpc.api.MessageExampleService;
import org.simplepoint.remoting.RemoteProvider;
import org.springframework.stereotype.Service;

/**
 * Implementation of the MessageExampleService interface.
 */
@Service
@RemoteProvider
public class MessageExampleServiceImpl implements MessageExampleService {
  @Override
  public MessageExample echo(String message) {
    MessageExample messageExample = new MessageExample();
    messageExample.setMessage("Echo: " + message);
    return messageExample;
  }
}
