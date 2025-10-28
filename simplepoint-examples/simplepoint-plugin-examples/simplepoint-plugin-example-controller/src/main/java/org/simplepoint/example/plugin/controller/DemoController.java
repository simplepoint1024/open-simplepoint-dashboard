package org.simplepoint.example.plugin.controller;

import org.simplepoint.example.plugin.service.DemoService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo controller class for the SimplePoint example plugin application.
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

  public final DemoService demoService;

  /**
   * Constructs a new DemoController instance.
   *
   * @param demoService the demo service
   */
  public DemoController(DemoService demoService) {
    this.demoService = demoService;
  }

  /**
   * Calls the demo service to say hello without arguments.
   *
   * @return a greeting message
   */
  @RequestMapping("/sayHelloNoArgs")
  public String sayHelloNoArgs() {
    return demoService.sayHelloNoArgs();
  }

  /**
   * Calls the demo service to say hello with a name argument.
   *
   * @param name the name to include in the greeting
   * @return a personalized greeting message
   */
  @RequestMapping("/sayHelloWithArgs")
  public String sayHelloWithArgs(@RequestParam("name") String name) {
    return demoService.sayHelloWithArgs(name);
  }

  /**
   * Calls the demo service to say hello with a name argument.
   *
   * @param name the name to include in the greeting
   * @return a personalized greeting message
   */
  @RequestMapping("/sayHelloWithArgs1")
  public String sayHelloWithArgs1(@RequestParam("name") String name) {
    return demoService.sayHelloWithArgs(name);
  }
}
