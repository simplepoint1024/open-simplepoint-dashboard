package org.simplepoint.amqprpc.provider;

import org.simplepoint.boot.starter.Boot;

/**
 * The main entry point for the AMQP RPC Provider application.
 * This class is annotated with @Boot to configure
 * the application and @EnableCaching to enable caching support.
 */
@Boot
public class ProviderApplication {

  /**
   * The main method that starts the AMQP RPC Provider application.
   * This method delegates to the Application.run() method to bootstrap the application.
   *
   * @param args the command-line arguments passed during application startup
   */
  public static void main(String[] args) {
    org.simplepoint.boot.starter.Application.run(ProviderApplication.class, args);
  }
}
