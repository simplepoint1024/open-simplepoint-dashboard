package org.simplepoint.router.example.consumer;

import com.simplepoint.service.router.annotation.EnableServiceRouter;
import org.simplepoint.boot.starter.Application;
import org.simplepoint.boot.starter.Boot;
import org.simplepoint.router.example.api.GreetingService;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Router example consumer application.
 */
@Boot
@EnableDiscoveryClient(autoRegister = true)
@EnableServiceRouter(basePackageClasses = GreetingService.class)
public class ConsumerApplication {

  /**
   * Main entrypoint.
   *
   * @param args startup args
   */
  public static void main(final String[] args) {
    Application.run(ConsumerApplication.class, args);
  }
}
