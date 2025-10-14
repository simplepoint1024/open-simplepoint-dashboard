package org.simplepoint.core.security;

import java.util.Collection;
import org.simplepoint.api.base.BaseDetailsService;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * A Spring Bean implementation of the AccessControlService interface.
 * This class provides default (mostly empty) implementations for all methods defined in the interface.
 * It can be extended to provide actual access control logic as needed.
 */
@Component
public class SpringBeanDetailsProviderService implements DetailsProviderService, ApplicationContextAware {
  private ApplicationContext applicationContext;

  /**
   * Retrieves a dialect (service) by its class type from the Spring application context.
   *
   * @param clazz the class type of the dialect to retrieve
   * @param <D>   the type of the dialect
   * @return an instance of the requested dialect
   * @throws BeansException if the bean could not be created
   */
  @Override
  public <D extends BaseDetailsService> D getDialect(Class<D> clazz) {
    return applicationContext.getBean(clazz);
  }

  @Override
  public <D extends BaseDetailsService> Collection<D> getDialects(Class<D> clazz) {
    return applicationContext.getBeansOfType(clazz).values();
  }

  /**
   * Sets the ApplicationContext that this object runs in.
   * This method is called by the Spring framework during bean initialization.
   *
   * @param applicationContext the ApplicationContext object to be used by this bean
   * @throws BeansException if the context could not be set
   */
  @Override
  public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
    this.applicationContext = applicationContext;
  }
}
