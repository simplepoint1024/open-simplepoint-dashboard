package com.simplepoint.service.router.web;

import com.simplepoint.service.router.invocation.RemoteRequest;
import com.simplepoint.service.router.invocation.RemoteResponse;
import com.simplepoint.service.router.invocation.ServiceInvocationDispatcher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoint used by remote service router consumers.
 */
@RestController
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
  @PostMapping("${simplepoint.service-router.provider.expose-path:/_simplepoint/service-router/invoke}")
  public RemoteResponse invoke(@RequestBody final RemoteRequest request) {
    return dispatcher.dispatch(request);
  }
}
