package org.simplepoint.amqprpc.consumer;

import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.amqp.rpc.annotation.EnableAmqpRemoteClients;

/**
 * The main entry point for the AMQP RPC Consumer application.
 * This class is annotated with @Boot to configure
 * the application.
 */
@Boot
@EnableAmqpRemoteClients(basePackages = "org.simplepoint.amqprpc")
public class ConsumerApplication {

  /**
   * The main method that starts the AMQP RPC Consumer application.
   * This method delegates to the Application.run() method to bootstrap the application.
   *
   * @param args the command-line arguments passed during application startup
   */
  public static void main(String[] args) {
    org.simplepoint.boot.starter.Application.run(ConsumerApplication.class, args);
  }
}
