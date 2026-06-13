package com.simplepoint.service.router.invocation;

import java.util.List;

/**
 * Service router remote invocation request.
 *
 * @param service routed service name
 * @param version routed service version
 * @param method routed method id
 * @param args invocation arguments
 * @param traceId trace id
 */
public record RemoteRequest(
    String service,
    String version,
    String method,
    List<Object> args,
    String traceId
) {
}
