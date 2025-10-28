package org.simplepoint.example.plugin.service.impl;

import org.simplepoint.example.plugin.service.DemoService;
import org.springframework.stereotype.Service;

/**
 * Implementation of the DemoService interface for the SimplePoint example plugin application.
 */
@Service
public class DemoServiceImpl implements DemoService {
  @Override
  public String sayHelloNoArgs() {
    return "Hello World";
  }

  @Override
  public String sayHelloWithArgs(String name) {
    return "Hello " + name;
  }
}
