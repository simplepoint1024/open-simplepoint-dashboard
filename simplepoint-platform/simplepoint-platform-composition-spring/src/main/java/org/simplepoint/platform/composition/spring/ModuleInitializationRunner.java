package org.simplepoint.platform.composition.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializer;
import org.simplepoint.platform.composition.ModulePlan;
import org.simplepoint.platform.composition.ModuleResources;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;

/** Prepares selected startup contributions before applying them in dependency order. */
public final class ModuleInitializationRunner implements ApplicationRunner {
  private final ModulePlan plan;
  private final ApplicationContext application;
  private final ModuleServiceCredentials credentials;

  /** Captures the authoritative selection, without performing remote work during bean creation. */
  public ModuleInitializationRunner(
      ModulePlan plan, ApplicationContext application, ModuleServiceCredentials credentials) {
    this.plan = plan;
    this.application = application;
    this.credentials = credentials;
  }

  @Override
  public void run(ApplicationArguments arguments) throws Exception {
    var actions = new ArrayList<PreparedTask>();
    var resourceCodes = new LinkedHashMap<String, String>();
    var resourceParents = new LinkedHashMap<String, Map<String, String>>();
    var resourceOwners = new LinkedHashMap<String, String>();
    for (var module : plan.modules())
      module.resourceOwner().ifPresent(owner -> resourceOwners.put(module.id(), owner));
    for (var module : plan.modules()) {
      for (var type : module.initializers()) {
        var clients = new LinkedHashMap<Class<?>, Object>();
        var declaration = type.getDeclaredAnnotation(ModuleInitialization.class);
        for (var contract : declaration.consumes()) {
          clients.put(
              contract,
              ModuleClientFactoryBean.boundClient(contract, module.id(), credentials, application));
        }
        var context =
            new InitializationContext(
                module.id(),
                module.resourceOwner(),
                Map.copyOf(clients),
                application.getClassLoader(),
                declaration.registersResources(),
                resourceCodes,
                resourceParents);
        var initializer = application.getBean(beanName(type), ModuleInitializer.class);
        var stage = declaration.stage();
        for (var task : Objects.requireNonNull(initializer.prepareTasks(context), type.getName())) {
          actions.add(
              new PreparedTask(
                  module.id(),
                  declaration.registersResources(),
                  stage,
                  task.order(),
                  task.action()));
        }
      }
    }
    actions.sort(
        java.util.Comparator.comparing(PreparedTask::stage).thenComparingInt(PreparedTask::order));
    for (var action : orderResources(actions, resourceCodes, resourceParents, resourceOwners)) {
      action.action().run();
    }
  }

  /** Stable dependency order, derived from selected declarations rather than module names. */
  static java.util.List<PreparedTask> orderResources(
      java.util.List<PreparedTask> tasks,
      Map<String, String> codes,
      Map<String, Map<String, String>> parents,
      Map<String, String> owners) {
    var providers = new LinkedHashMap<String, Integer>();
    for (int index = 0; index < tasks.size(); index++) {
      var task = tasks.get(index);
      if (task.resourceRegistrar() && providers.putIfAbsent(task.moduleId(), index) != null) {
        throw new ModuleCompositionException(
            "MULTIPLE_RESOURCE_INITIALIZATION_TASKS", task.moduleId());
      }
    }
    var dependencies = new java.util.ArrayList<java.util.Set<Integer>>();
    for (int index = 0; index < tasks.size(); index++) {
      var task = tasks.get(index);
      var required = new java.util.HashSet<Integer>();
      if (task.resourceRegistrar()) {
        for (var reference : parents.getOrDefault(task.moduleId(), Map.of()).entrySet()) {
          String provider = codes.get(reference.getKey());
          // Remote parents are resolved and validated under the identity catalog lock by sync.
          if (provider == null) {
            if (owners.containsValue(reference.getValue())) {
              throw new ModuleCompositionException("RESOURCE_PARENT_MISSING", reference.getKey());
            }
            continue;
          }
          if (!reference.getValue().equals(owners.get(provider))
              || !providers.containsKey(provider)) {
            throw new ModuleCompositionException(
                "RESOURCE_PARENT_OWNER_CONFLICT", reference.getKey());
          }
          int predecessor = providers.get(provider);
          if (predecessor != index) {
            if (tasks.get(predecessor).stage() != task.stage()) {
              throw new ModuleCompositionException(
                  "RESOURCE_PARENT_STAGE_CONFLICT", reference.getKey());
            }
            required.add(predecessor);
          }
        }
      }
      dependencies.add(required);
    }
    var result = new ArrayList<PreparedTask>();
    var completed = new java.util.HashSet<Integer>();
    while (result.size() < tasks.size()) {
      Integer next = null;
      for (int index = 0; index < tasks.size(); index++) {
        if (!completed.contains(index) && completed.containsAll(dependencies.get(index))) {
          next = index;
          break;
        }
      }
      if (next == null)
        throw new ModuleCompositionException("CYCLIC_RESOURCE_PARENTS", "selected declarations");
      completed.add(next);
      result.add(tasks.get(next));
    }
    return java.util.List.copyOf(result);
  }

  static String beanName(Class<? extends ModuleInitializer> type) {
    return type.getName() + "#moduleInitializer";
  }

  record PreparedTask(
      String moduleId,
      boolean resourceRegistrar,
      org.simplepoint.platform.composition.ModuleInitializationStage stage,
      int order,
      Runnable action) {}

  private record InitializationContext(
      String moduleId,
      java.util.Optional<String> resourceOwner,
      Map<Class<?>, Object> clients,
      ClassLoader loader,
      boolean resourceRegistrar,
      Map<String, String> resourceCodes,
      Map<String, Map<String, String>> resourceParents)
      implements ModuleInitializationContext {
    @Override
    public void claimResourceCodes(java.util.Set<String> codes) {
      if (!resourceRegistrar || resourceOwner.isEmpty()) {
        throw new ModuleCompositionException("UNDECLARED_RESOURCE_CLAIM", moduleId);
      }
      if (codes == null
          || codes.size() > 10_000
          || codes.stream().anyMatch(code -> code == null || code.isBlank())) {
        throw new ModuleCompositionException("INVALID_RESOURCE_DECLARATION", moduleId);
      }
      for (String code : codes) {
        var previous = resourceCodes.putIfAbsent(code, moduleId);
        if (previous != null) {
          throw new ModuleCompositionException(
              "DUPLICATE_RESOURCE_CODE", code + ": " + previous + ", " + moduleId);
        }
      }
    }

    @Override
    public void requireResourceParents(Map<String, String> parents) {
      if (!resourceRegistrar || resourceOwner.isEmpty()) {
        throw new ModuleCompositionException("UNDECLARED_RESOURCE_DEPENDENCY", moduleId);
      }
      if (parents == null
          || parents.size() > 10_000
          || parents.entrySet().stream()
              .anyMatch(
                  entry ->
                      entry.getKey() == null
                          || entry.getKey().isBlank()
                          || entry.getValue() == null
                          || entry.getValue().isBlank())) {
        throw new ModuleCompositionException("INVALID_RESOURCE_DEPENDENCY", moduleId);
      }
      if (resourceParents.putIfAbsent(moduleId, Map.copyOf(parents)) != null) {
        throw new ModuleCompositionException("DUPLICATE_RESOURCE_DEPENDENCY", moduleId);
      }
    }

    @Override
    public <T> T client(Class<T> contract) {
      Object client = clients.get(contract);
      if (client == null) {
        throw new ModuleCompositionException(
            "UNDECLARED_INITIALIZER_CLIENT", moduleId + " -> " + contract.getName());
      }
      return contract.cast(client);
    }

    @Override
    public byte[] resource(String relativePath) {
      return ModuleResources.read(moduleId, loader, relativePath);
    }
  }
}
