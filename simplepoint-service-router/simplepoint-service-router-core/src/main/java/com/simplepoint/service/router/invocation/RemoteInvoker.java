package com.simplepoint.service.router.invocation;

import com.simplepoint.service.router.routing.ServiceRoute;

/**
 * Remote invocation abstraction.
 */
public interface RemoteInvoker {

  /**
   * Invokes a remote provider.
   *
   * @param route provider route
   * @param request invocation request
   * @return invocation response
   */
  RemoteResponse invoke(ServiceRoute route, RemoteRequest request);
}
