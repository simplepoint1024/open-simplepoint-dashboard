package com.simplepoint.service.router.registry;

import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import java.util.Collection;
import java.util.Optional;

/**
 * Registry for local routed service providers.
 */
public interface LocalServiceRegistry {

  /**
   * Finds a local provider.
   *
   * @param service service name
   * @param version service version
   * @return local provider
   */
  Optional<LocalRoutedService> find(String service, String version);

  /**
   * Finds a local provider by metadata.
   *
   * @param metadata service metadata
   * @return local provider
   */
  default Optional<LocalRoutedService> find(final RoutedServiceMetadata metadata) {
    return find(metadata.name(), metadata.version());
  }

  /**
   * Returns all local providers.
   *
   * @return providers
   */
  Collection<LocalRoutedService> all();
}
