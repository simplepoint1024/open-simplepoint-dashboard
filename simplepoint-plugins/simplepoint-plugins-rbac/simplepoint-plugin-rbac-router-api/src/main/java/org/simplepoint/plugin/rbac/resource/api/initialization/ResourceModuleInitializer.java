package org.simplepoint.plugin.rbac.resource.api.initialization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.simplepoint.core.permission.PermissionPageDescriptor;
import org.simplepoint.core.permission.ResourcePermissionCatalog;
import org.simplepoint.platform.composition.ModuleCompositionException;
import org.simplepoint.platform.composition.ModuleInitialization;
import org.simplepoint.platform.composition.ModuleInitializationContext;
import org.simplepoint.platform.composition.ModuleInitializer;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.service.ResourceRegistrationService;
import org.springframework.beans.factory.ObjectProvider;

/** Registers explicitly selected module resources under their stable logical owner. */
@ModuleInitialization(consumes = ResourceRegistrationService.class, registersResources = true)
public final class ResourceModuleInitializer implements ModuleInitializer {
  private final ObjectProvider<ResourcePermissionCatalog> catalog;
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Receives optional local permission descriptors; the RPC client is bound by the module runner.
   */
  public ResourceModuleInitializer(ObjectProvider<ResourcePermissionCatalog> catalog) {
    this.catalog = catalog;
  }

  @Override
  public Runnable prepare(ModuleInitializationContext context) throws Exception {
    String owner =
        context
            .resourceOwner()
            .orElseThrow(
                () ->
                    new ModuleCompositionException(
                        "MODULE_RESOURCE_OWNER_REQUIRED", context.moduleId()));
    var declarations =
        org.simplepoint.security.resource.ResourceDeclarationDocument.read(
            context.moduleId(), owner, context.resource("resources.json"));
    context.claimResourceCodes(declarations.codes());
    Set<ResourceDeclaration> resources =
        mapper.convertValue(
            declarations.resources(), new TypeReference<LinkedHashSet<ResourceDeclaration>>() {});
    var parents = new java.util.LinkedHashMap<String, String>();
    var queue = new ArrayDeque<>(resources);
    while (!queue.isEmpty()) {
      var resource = queue.removeFirst();
      var reference = resource.getParentReference();
      if (reference != null && !owner.equals(reference.owner())) {
        if (reference.code() == null
            || reference.code().isBlank()
            || reference.owner() == null
            || reference.owner().isBlank()) {
          throw new ModuleCompositionException("INVALID_RESOURCE_PARENT", context.moduleId());
        }
        var previous = parents.putIfAbsent(reference.code(), reference.owner());
        if (previous != null && !previous.equals(reference.owner())) {
          throw new ModuleCompositionException("CONFLICTING_RESOURCE_PARENT", reference.code());
        }
      }
      if (resource.getChildren() != null) queue.addAll(resource.getChildren());
    }
    if (!parents.isEmpty()) context.requireResourceParents(Map.copyOf(parents));
    enrich(resources, catalog.getIfAvailable());
    var client = context.client(ResourceRegistrationService.class);
    return () -> {
      if (!resources.isEmpty()) {
        client.sync(owner, resources);
      }
    };
  }

  /** Attaches the same permission metadata in modular and legacy startup paths. */
  public static void enrich(Set<ResourceDeclaration> resources, ResourcePermissionCatalog catalog) {
    Map<String, PermissionPageDescriptor> entries = catalog == null ? Map.of() : catalog.entries();
    var queue = new ArrayDeque<>(resources);
    while (!queue.isEmpty()) {
      var resource = queue.removeFirst();
      var descriptor = entries.get(resource.getCode());
      resource.setPermissionMetadata(descriptor == null ? null : descriptor.toJson());
      if (resource.getChildren() != null) {
        queue.addAll(resource.getChildren());
      }
    }
  }
}
