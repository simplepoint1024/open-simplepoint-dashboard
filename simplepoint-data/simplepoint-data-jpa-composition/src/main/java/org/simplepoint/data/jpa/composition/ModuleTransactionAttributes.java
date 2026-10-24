package org.simplepoint.data.jpa.composition;

import java.lang.reflect.Method;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleStorageBindings;
import org.springframework.transaction.interceptor.DelegatingTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/** Decorates the existing annotation policy before advisors instantiate their interceptor. */
final class ModuleTransactionAttributes implements TransactionAttributeSource {
  private final TransactionAttributeSource delegate;
  private final ModulePlan modules;
  private final ModuleStorageBindings bindings;

  ModuleTransactionAttributes(
      TransactionAttributeSource delegate, ModulePlan modules, ModuleStorageBindings bindings) {
    this.delegate = delegate;
    this.modules = modules;
    this.bindings = bindings;
  }

  @Override
  public boolean isCandidateClass(Class<?> target) {
    return delegate.isCandidateClass(target);
  }

  @Override
  public TransactionAttribute getTransactionAttribute(Method method, Class<?> target) {
    var attribute = delegate.getTransactionAttribute(method, target);
    if (attribute == null || target == null) {
      return attribute;
    }
    var owner =
        modules
            .ownerOf(target)
            .filter(module -> module.configuration().getClassLoader() == target.getClassLoader());
    if (owner.isEmpty()) {
      return attribute;
    }
    String manager = bindings.require(owner.get().id()).transactionManager();
    if (attribute.getQualifier() != null
        && !attribute.getQualifier().isBlank()
        && !attribute.getQualifier().equals(manager)) {
      throw new ModuleCompositionException("FOREIGN_MODULE_TRANSACTION_MANAGER", owner.get().id());
    }
    return new DelegatingTransactionAttribute(attribute) {
      @Override
      public String getQualifier() {
        return manager;
      }
    };
  }
}
