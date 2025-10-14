/*
 * Copyright (c) 2025 Jinxu Liu or Organization
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 */

package org.simplepoint.gateway.server;

import org.simplepoint.boot.starter.Boot;
import org.simplepoint.data.amqp.rpc.annotation.EnableAmqpRemoteClients;
import org.springframework.cache.annotation.EnableCaching;

/**
 * The main entry point for the Gateway application.
 * This class is annotated with @Boot to enable application startup,
 * initializing necessary configurations and launching the application.
 */
@Boot
@EnableCaching
@EnableAmqpRemoteClients(basePackages = "org.simplepoint")
public class Host {

  /**
   * The main method that starts the Gateway application.
   * This method delegates to the Application.run() method to bootstrap the application.
   *
   * @param args the command-line arguments passed during application startup
   */
  public static void main(String[] args) {
    org.simplepoint.boot.starter.Application.run(Host.class, args);
  }
}
