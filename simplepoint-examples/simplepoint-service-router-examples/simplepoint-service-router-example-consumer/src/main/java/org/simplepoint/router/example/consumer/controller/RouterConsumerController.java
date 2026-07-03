package org.simplepoint.router.example.consumer.controller;

import org.simplepoint.router.example.api.GreetingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for service-router consumer example.
 */
@RestController
@RequestMapping("/example")
public class RouterConsumerController {
  private final GreetingService greetingService;

  /**
   * Creates a service-router consumer controller.
   *
   * @param greetingService routed greeting service
   */
  public RouterConsumerController(final GreetingService greetingService) {
    this.greetingService = greetingService;
  }

  /**
   * Calls routed greeting service.
   *
   * @param name name
   * @return greeting result
   */
  @GetMapping("/greet")
  public String greet(@RequestParam(defaultValue = "simplepoint", name = "name") final String name) {
    return greetingService.greet(name);
  }
}
