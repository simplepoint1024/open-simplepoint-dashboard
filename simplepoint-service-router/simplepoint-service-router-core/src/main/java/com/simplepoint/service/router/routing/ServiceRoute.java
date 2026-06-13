package com.simplepoint.service.router.routing;

import java.net.URI;
import java.util.Map;

/**
 * A selected provider route.
 *
 * @param serviceId discovery service id
 * @param instanceId provider instance id
 * @param uri provider base URI
 * @param metadata provider metadata
 */
public record ServiceRoute(
    String serviceId,
    String instanceId,
    URI uri,
    Map<String, String> metadata
) {
}
