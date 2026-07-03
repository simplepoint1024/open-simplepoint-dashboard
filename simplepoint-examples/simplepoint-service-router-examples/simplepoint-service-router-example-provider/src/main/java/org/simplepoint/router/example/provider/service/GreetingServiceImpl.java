package org.simplepoint.router.example.provider.service;

import org.simplepoint.router.example.api.GreetingService;
import org.springframework.stereotype.Service;

/**
 * Greeting service provider implementation.
 */
@Service
public class GreetingServiceImpl implements GreetingService {

  @Override
  public String greet(final String name) {
    return "Hello, " + name + " from provider";
  }
}
