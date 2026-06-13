package org.simplepoint.router.example.api;

import com.simplepoint.service.router.annotation.RoutedMethod;
import com.simplepoint.service.router.annotation.RoutedService;

/**
 * Greeting service contract for router example.
 */
@RoutedService(name = "sample.greeting-service")
public interface GreetingService {

  /**
   * Returns a greeting for the user.
   *
   * @param name user name
   * @return greeting text
   */
  @RoutedMethod("greet")
  String greet(String name);
}
