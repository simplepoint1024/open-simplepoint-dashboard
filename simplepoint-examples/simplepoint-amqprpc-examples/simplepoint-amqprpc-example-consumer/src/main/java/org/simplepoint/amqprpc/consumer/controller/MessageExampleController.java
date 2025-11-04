package org.simplepoint.amqprpc.consumer.controller;

import org.simplepoint.amqprpc.api.MessageExample;
import org.simplepoint.amqprpc.api.MessageExampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for handling message example requests.
 */
@RestController
@RequestMapping("/message")
public class MessageExampleController {
  private final MessageExampleService messageExampleService;

  /**
   * Constructor for MessageExampleController.
   *
   * @param messageExampleService the message example service
   */
  public MessageExampleController(MessageExampleService messageExampleService) {
    this.messageExampleService = messageExampleService;
  }

  /**
   * Endpoint to get a message example.
   *
   * @return the message example
   */
  @GetMapping
  public MessageExample message() {
    return messageExampleService.echo("Hello from Consumer!");
  }
}
