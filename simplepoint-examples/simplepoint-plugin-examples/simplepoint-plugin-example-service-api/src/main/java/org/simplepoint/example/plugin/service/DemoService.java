package org.simplepoint.example.plugin.service;

/**
 * Demo service interface for the SimplePoint example plugin application.
 */
public interface DemoService {

  /**
   * Method to say hello without any arguments.
   *
   * @return A greeting message.
   */
  String sayHelloNoArgs();

  /**
   * Method to say hello with a name argument.
   *
   * @param name The name to include in the greeting.
   * @return A personalized greeting message.
   */
  String sayHelloWithArgs(String name);
}
