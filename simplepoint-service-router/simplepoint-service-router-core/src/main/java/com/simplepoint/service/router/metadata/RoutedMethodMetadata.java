package com.simplepoint.service.router.metadata;

import java.util.List;

/**
 * Routed method metadata.
 *
 * @param name Java method name
 * @param methodId stable routed method id
 * @param parameterTypes Java parameter type names
 * @param returnType Java return type name
 */
public record RoutedMethodMetadata(
    String name,
    String methodId,
    List<String> parameterTypes,
    String returnType
) {
}
