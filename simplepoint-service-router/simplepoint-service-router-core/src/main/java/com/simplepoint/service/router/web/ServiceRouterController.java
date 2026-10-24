package com.simplepoint.service.router.web;

import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoint used by remote service router consumers.
 */
@RestController
@ConditionalOnProperty(
    prefix = "simplepoint.service-router.provider", name = "enabled", matchIfMissing = true)
public class ServiceRouterController {

  private final ServiceInvocationDispatcher dispatcher;

  /**
   * Creates a service router HTTP controller.
   *
   * @param dispatcher inbound invocation dispatcher
   */
  public ServiceRouterController(final ServiceInvocationDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * Invokes a routed service provider.
   *
   * @param request remote request
   * @return remote response
   */
  @PostMapping(
      value = "${simplepoint.service-router.provider.expose-path:/_simplepoint/service-router/invoke}",
      consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public byte[] invoke(@RequestBody final byte[] request) {
    return dispatcher.dispatchJson(request);
  }
}
