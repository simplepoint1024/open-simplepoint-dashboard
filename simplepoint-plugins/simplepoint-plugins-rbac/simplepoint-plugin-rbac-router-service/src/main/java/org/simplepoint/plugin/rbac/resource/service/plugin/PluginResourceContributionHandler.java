package org.simplepoint.plugin.rbac.resource.service.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.manifest.PluginManifest;
import org.simplepoint.plugin.rbac.resource.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceType;
import org.simplepoint.security.service.ResourceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers RBAC resource contributions declared by plugin manifests.
 */
@Service
public class PluginResourceContributionHandler
    implements PluginLifecycleHandler, PluginInstallValidator, PluginInstallBatchValidator {

  private static final int ORDER = 100;

  private final RemoteModuleRepository remoteModuleRepository;
  private final ResourceRepository resourceRepository;
  private final ResourceService resourceService;

  /**
   * Creates the plugin resource contribution handler.
   */
  public PluginResourceContributionHandler(
      RemoteModuleRepository remoteModuleRepository,
      ResourceRepository resourceRepository,
      ResourceService resourceService
  ) {
    this.remoteModuleRepository = remoteModuleRepository;
    this.resourceRepository = resourceRepository;
    this.resourceService = resourceService;
  }

  @Override
  public int order() {
    return ORDER;
  }

  @Override
  public boolean supports(Plugin plugin) {
    if (plugin == null || plugin.manifest() == null) {
      return false;
    }
    PluginManifest manifest = plugin.manifest();
    return !remotes(manifest).isEmpty() || !safeList(manifest.getResources()).isEmpty();
  }

  @Override
  public boolean supports(List<Plugin> plugins) {
    return plugins != null && plugins.stream().anyMatch(this::supports);
  }

  @Override
  public void validate(Plugin plugin) {
    if (!supports(plugin)) {
      return;
    }
    PluginManifest manifest = plugin.manifest();
    String pluginId = requireText(manifest.getId(), "Plugin id is required");
    validateRemotes(pluginId, manifest);
    validateResourceDeclarations(pluginId, flattenResources(safeList(manifest.getResources())));
  }

  @Override
  public void validate(List<Plugin> plugins) {
    if (!supports(plugins)) {
      return;
    }
    Set<String> remoteNames = new LinkedHashSet<>();
    Set<String> remoteEntries = new LinkedHashSet<>();
    Set<String> codes = new LinkedHashSet<>();
    Set<String> paths = new LinkedHashSet<>();
    for (Plugin plugin : plugins.stream().filter(this::supports).toList()) {
      String pluginId = requireText(plugin.manifest().getId(), "Plugin id is required");
      for (PluginManifest.RemoteContribution remote : remotes(plugin.manifest())) {
        String name = requireText(remote.getName(), "Remote name is required for plugin " + pluginId);
        String entry = requireText(remote.getEntry(), "Remote entry is required for plugin " + pluginId);
        if (!remoteNames.add(name)) {
          throw new IllegalStateException("frontend remote '" + name + "' is declared by multiple plugin candidates");
        }
        if (!remoteEntries.add(entry)) {
          throw new IllegalStateException("frontend remote entry '" + entry
              + "' is declared by multiple plugin candidates");
        }
      }
      for (PluginManifest.ResourceContribution resource : flattenResources(safeList(plugin.manifest().getResources()))) {
        String code = requireText(resource.getCode(), "Resource code is required for plugin " + pluginId);
        if (!codes.add(code)) {
          throw new IllegalStateException("Duplicate resource code in plugin batch: " + code);
        }
        if (hasText(resource.getPath()) && !paths.add(resource.getPath())) {
          throw new IllegalStateException("Duplicate resource path in plugin batch: " + resource.getPath());
        }
      }
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void installed(Plugin plugin) {
    PluginManifest manifest = plugin.manifest();
    String pluginId = requireText(manifest.getId(), "Plugin id is required");
    registerRemotes(plugin, pluginId, manifest);
    Set<ResourceDeclaration> resources = safeList(manifest.getResources()).stream()
        .map(resource -> toDeclaration(pluginId, resource))
        .collect(Collectors.toCollection(LinkedHashSet::new));
    resourceService.sync(pluginId, resources);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void uninstalling(Plugin plugin) {
    String pluginId = requireText(plugin.manifest().getId(), "Plugin id is required");
    List<String> resourceIds = resourceService.findAll(Map.of("pluginId", pluginId)).stream()
        .map(Resource::getId)
        .filter(this::hasText)
        .toList();
    if (!resourceIds.isEmpty()) {
      resourceService.removeByIds(resourceIds);
    }
    List<String> remoteIds = remoteModuleRepository.findAll(Map.of("pluginId", pluginId)).stream()
        .map(MicroModule::getId)
        .filter(this::hasText)
        .toList();
    if (!remoteIds.isEmpty()) {
      remoteModuleRepository.deleteByIds(remoteIds);
    }
  }

  private void validateRemotes(String pluginId, PluginManifest manifest) {
    Set<String> names = new LinkedHashSet<>();
    Set<String> entries = new LinkedHashSet<>();
    for (PluginManifest.RemoteContribution remote : remotes(manifest)) {
      String name = requireText(remote.getName(), "Remote name is required for plugin " + pluginId);
      String entry = requireText(remote.getEntry(), "Remote entry is required for plugin " + pluginId);
      if (!names.add(name)) {
        throw new IllegalStateException("Duplicate remote name in plugin " + pluginId + ": " + name);
      }
      if (!entries.add(entry)) {
        throw new IllegalStateException("Duplicate remote entry in plugin " + pluginId + ": " + entry);
      }
      List<MicroModule> existingByName = remoteModuleRepository.findAll(Map.of("serviceName", name));
      for (MicroModule module : existingByName) {
        if (hasText(module.getPluginId()) && !pluginId.equals(module.getPluginId())) {
          throw new IllegalStateException("frontend remote '" + name + "' is not owned by plugin " + pluginId);
        }
      }
      List<MicroModule> existingByEntry = remoteModuleRepository.findAll(Map.of("entry", entry));
      for (MicroModule module : existingByEntry) {
        if (hasText(module.getPluginId()) && !pluginId.equals(module.getPluginId())) {
          throw new IllegalStateException("frontend remote entry '" + entry + "' is not owned by plugin " + pluginId);
        }
      }
    }
  }

  private void validateResourceDeclarations(String pluginId, List<PluginManifest.ResourceContribution> resources) {
    Set<String> localCodes = new LinkedHashSet<>();
    Set<String> localPaths = new LinkedHashSet<>();
    for (PluginManifest.ResourceContribution resource : resources) {
      String code = requireText(resource.getCode(), "Resource code is required for plugin " + pluginId);
      requireText(resource.getName(), "Resource name is required for " + code);
      if (!localCodes.add(code)) {
        throw new IllegalStateException("Duplicate resource code in plugin " + pluginId + ": " + code);
      }
      if (hasText(resource.getPath()) && !localPaths.add(resource.getPath())) {
        throw new IllegalStateException("Duplicate resource path in plugin " + pluginId + ": " + resource.getPath());
      }
    }
    Collection<Resource> existing = resourceRepository.findAllByCodes(localCodes);
    List<String> claimedByOtherPlugin = existing.stream()
        .filter(resource -> hasText(resource.getPluginId()) && !pluginId.equals(resource.getPluginId()))
        .map(Resource::getCode)
        .toList();
    if (!claimedByOtherPlugin.isEmpty()) {
      throw new IllegalStateException("Resource code already owned by another plugin: "
          + String.join(",", claimedByOtherPlugin));
    }
  }

  private void registerRemotes(Plugin plugin, String pluginId, PluginManifest manifest) {
    for (PluginManifest.RemoteContribution remote : remotes(manifest)) {
      MicroModule module = new MicroModule();
      module.setPluginId(pluginId);
      module.setPluginVersion(manifest.getVersion());
      module.setRemoteVersion(remote.getVersion());
      module.setPluginArtifactSha256(plugin.artifact() == null ? null : plugin.artifact().sha256());
      module.setServiceName(remote.getName());
      module.setDisplayName(firstNonBlank(remote.getModule(), remote.getName()));
      module.setEntry(remote.getEntry());
      module.setDescription(manifest.getDescription());
      List<MicroModule> existing = remoteModuleRepository.findAll(Map.of("pluginId", pluginId, "serviceName", remote.getName()));
      if (!existing.isEmpty()) {
        module.setId(existing.get(0).getId());
        remoteModuleRepository.updateById(module);
      } else {
        remoteModuleRepository.save(module);
      }
    }
  }

  private ResourceDeclaration toDeclaration(String pluginId, PluginManifest.ResourceContribution contribution) {
    ResourceDeclaration declaration = new ResourceDeclaration();
    declaration.setPluginId(pluginId);
    declaration.setCode(contribution.getCode());
    declaration.setName(contribution.getName());
    declaration.setTitle(contribution.getTitle());
    declaration.setLabel(contribution.getLabel());
    declaration.setType(parseType(contribution.getType()));
    declaration.setPath(contribution.getPath());
    declaration.setComponent(contribution.getComponent());
    declaration.setIcon(contribution.getIcon());
    declaration.setRouteKind(contribution.getRouteKind());
    declaration.setMethod(contribution.getMethod());
    declaration.setPattern(contribution.getPattern());
    declaration.setDescription(contribution.getDescription());
    declaration.setSort(contribution.getSort());
    declaration.setPublicAccess(contribution.getPublicAccess());
    declaration.setRequireOrgTenant(contribution.getRequireOrgTenant());
    declaration.setGrantable(contribution.getGrantable());
    declaration.setDisabled(contribution.getDisabled());
    declaration.setDanger(contribution.getDanger());
    if (contribution.getChildren() != null && !contribution.getChildren().isEmpty()) {
      declaration.setChildren(contribution.getChildren().stream()
          .map(child -> toDeclaration(pluginId, child))
          .collect(Collectors.toCollection(LinkedHashSet::new)));
    }
    return declaration;
  }

  private ResourceType parseType(String type) {
    if (!hasText(type)) {
      return null;
    }
    return ResourceType.valueOf(type.trim().toUpperCase());
  }

  private List<PluginManifest.ResourceContribution> flattenResources(List<PluginManifest.ResourceContribution> resources) {
    List<PluginManifest.ResourceContribution> result = new ArrayList<>();
    for (PluginManifest.ResourceContribution resource : resources) {
      result.add(resource);
      if (resource.getChildren() != null && !resource.getChildren().isEmpty()) {
        result.addAll(flattenResources(resource.getChildren()));
      }
    }
    return result;
  }

  private List<PluginManifest.RemoteContribution> remotes(PluginManifest manifest) {
    if (manifest.getFrontend() == null) {
      return List.of();
    }
    return safeList(manifest.getFrontend().getRemotes());
  }

  private <T> List<T> safeList(List<T> data) {
    return data == null ? List.of() : data;
  }

  private String requireText(String value, String message) {
    if (!hasText(value)) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
