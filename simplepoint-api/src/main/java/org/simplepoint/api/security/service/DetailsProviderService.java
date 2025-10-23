package org.simplepoint.api.security.service;


import java.util.Collection;
import org.simplepoint.api.base.BaseDetailsService;

/**
 * Details Provider Service.
 */
public interface DetailsProviderService {

  /**
   * Get dialect by class type.
   *
   * @param clazz dialect class type.
   * @param <D>   dialect type.
   * @return dialect.
   */
  <D extends BaseDetailsService> D getDialect(Class<D> clazz);

  /**
   * Get all dialects by class type.
   *
   * @param clazz dialect class type.
   * @param <D>   dialect type.
   * @return dialect collection.
   */
  <D extends BaseDetailsService> Collection<D> getDialects(Class<D> clazz);
}
