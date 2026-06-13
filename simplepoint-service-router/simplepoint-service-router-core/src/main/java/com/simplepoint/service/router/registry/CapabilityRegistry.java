package com.simplepoint.service.router.registry;

import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import java.util.List;

/**
 * Provides local service router capabilities for publication or diagnostics.
 */
public class CapabilityRegistry {

  private final LocalServiceRegistry localServiceRegistry;

  /**
   * Creates a capability registry.
   *
   * @param localServiceRegistry local provider registry
   */
  public CapabilityRegistry(final LocalServiceRegistry localServiceRegistry) {
    this.localServiceRegistry = localServiceRegistry;
  }

  /**
   * Returns local capabilities.
   *
   * @return local service capabilities
   */
  public List<RoutedServiceMetadata> capabilities() {
    return localServiceRegistry.all().stream()
        .map(LocalRoutedService::metadata)
        .toList();
  }
}
