package com.simplepoint.service.router.registry;

import com.simplepoint.service.router.metadata.RoutedServiceMetadata;
import java.util.function.Supplier;

/**
 * Local routed service provider.
 *
 * @param metadata service metadata
 * @param beanSupplier provider bean supplier
 * @param interfaceType routed service interface
 */
public record LocalRoutedService(
    RoutedServiceMetadata metadata,
    Supplier<Object> beanSupplier,
    Class<?> interfaceType
) {

  /**
   * Returns the provider bean. The bean is resolved lazily to avoid forcing provider creation while the
   * service-router registry is being initialized.
   *
   * @return provider bean
   */
  public Object bean() {
    return beanSupplier.get();
  }
}
