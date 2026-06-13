package com.simplepoint.service.router.metadata;

import java.util.List;

/**
 * Routed service metadata.
 *
 * @param interfaceName Java interface name
 * @param name stable routed service name
 * @param version service contract version
 * @param timeout invocation timeout in milliseconds
 * @param retries retry count
 * @param fallbackClassName fallback class name
 * @param methods routed methods
 */
public record RoutedServiceMetadata(
    String interfaceName,
    String name,
    String version,
    long timeout,
    int retries,
    String fallbackClassName,
    List<RoutedMethodMetadata> methods
) {
}
