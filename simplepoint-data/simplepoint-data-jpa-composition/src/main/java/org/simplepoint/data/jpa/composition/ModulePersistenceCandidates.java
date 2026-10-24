package org.simplepoint.data.jpa.composition;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import javax.sql.DataSource;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.AutowireCandidateResolver;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Binds domain injection by its declaring module while preserving ordinary infrastructure lookup.
 */
final class ModulePersistenceCandidates implements AutowireCandidateResolver {
  private final AutowireCandidateResolver delegate;
  private final ModulePlan modules;
  private final ModuleStorageBindings bindings;

  ModulePersistenceCandidates(
      AutowireCandidateResolver delegate, ModulePlan modules, ModuleStorageBindings bindings) {
    this.delegate = delegate;
    this.modules = modules;
    this.bindings = bindings;
  }

  @Override
  public boolean isAutowireCandidate(BeanDefinitionHolder holder, DependencyDescriptor descriptor) {
    String name = ownedResource(descriptor);
    return (name == null
            || name.equals(holder.getBeanName())
            || List.of(holder.getAliases()).contains(name))
        && delegate.isAutowireCandidate(holder, descriptor);
  }

  private String ownedResource(DependencyDescriptor descriptor) {
    Class<?> type = descriptor.getDependencyType();
    if (type != EntityManager.class
        && type != EntityManagerFactory.class
        && type != DataSource.class
        && type != JdbcTemplate.class
        && type != JdbcOperations.class
        && type != PlatformTransactionManager.class) {
      return null;
    }
    Class<?> declaring =
        descriptor.getMethodParameter() == null
            ? descriptor.getMember().getDeclaringClass()
            : descriptor.getMethodParameter().getContainingClass();
    var owner =
        modules
            .ownerOf(declaring)
            .filter(
                module -> module.configuration().getClassLoader() == declaring.getClassLoader());
    if (owner.isEmpty()) {
      return null;
    }
    var binding = bindings.require(owner.get().id());
    if (type == EntityManager.class) {
      return binding.entityManager();
    }
    if (type == EntityManagerFactory.class) {
      return binding.entityManagerFactory();
    }
    if (type == DataSource.class) {
      return binding.dataSource();
    }
    if (type == PlatformTransactionManager.class) {
      return binding.transactionManager();
    }
    return binding.jdbcTemplate();
  }

  @Override
  public boolean isRequired(DependencyDescriptor descriptor) {
    return delegate.isRequired(descriptor);
  }

  @Override
  public boolean hasQualifier(DependencyDescriptor descriptor) {
    return delegate.hasQualifier(descriptor);
  }

  @Override
  public String getSuggestedName(DependencyDescriptor descriptor) {
    return delegate.getSuggestedName(descriptor);
  }

  @Override
  public Object getSuggestedValue(DependencyDescriptor descriptor) {
    return delegate.getSuggestedValue(descriptor);
  }

  @Override
  public Object getLazyResolutionProxyIfNecessary(DependencyDescriptor descriptor, String name) {
    return delegate.getLazyResolutionProxyIfNecessary(descriptor, name);
  }

  @Override
  public Class<?> getLazyResolutionProxyClass(DependencyDescriptor descriptor, String name) {
    return delegate.getLazyResolutionProxyClass(descriptor, name);
  }

  @Override
  public AutowireCandidateResolver cloneIfNecessary() {
    return new ModulePersistenceCandidates(delegate.cloneIfNecessary(), modules, bindings);
  }
}
