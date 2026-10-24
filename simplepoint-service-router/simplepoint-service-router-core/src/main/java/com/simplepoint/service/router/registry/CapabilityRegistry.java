package com.simplepoint.service.router.registry;

import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import java.util.List;

/**
 * Provides local service router capabilities for publication or diagnostics.
 */
public class CapabilityRegistry {

  private final LocalServiceRegistry localServiceRegistry;

  private final boolean exportEnabled;

  /**
   * Creates a capability registry.
   *
   * @param localServiceRegistry local provider registry
   */
  public CapabilityRegistry(final LocalServiceRegistry localServiceRegistry) {
    this(localServiceRegistry, true);
  }

  /**
   * Creates an export view without changing the services available to local consumers.
   *
   * @param localServiceRegistry local provider registry
   * @param exportEnabled whether this host publishes remote capabilities
   */
  public CapabilityRegistry(
      final LocalServiceRegistry localServiceRegistry, final boolean exportEnabled) {
    this.localServiceRegistry = localServiceRegistry;
    this.exportEnabled = exportEnabled;
  }

  /**
   * Returns local capabilities.
   *
   * @return local service capabilities
   */
  public List<RoutedServiceMetadata> capabilities() {
    if (!exportEnabled) {
      return List.of();
    }
    return localServiceRegistry.all().stream()
        .map(LocalRoutedService::metadata)
        .toList();
  }
}
