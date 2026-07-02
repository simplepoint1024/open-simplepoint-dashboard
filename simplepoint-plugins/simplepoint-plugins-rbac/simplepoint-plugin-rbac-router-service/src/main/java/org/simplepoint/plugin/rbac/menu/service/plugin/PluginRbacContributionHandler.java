package org.simplepoint.plugin.rbac.menu.service.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.plugin.api.Plugin;
import org.simplepoint.plugin.api.PluginInstallBatchValidator;
import org.simplepoint.plugin.api.PluginInstallValidator;
import org.simplepoint.plugin.api.PluginLifecycleHandler;
import org.simplepoint.plugin.api.manifest.PluginManifest;
import org.simplepoint.plugin.rbac.core.api.repository.PermissionsRepository;
import org.simplepoint.plugin.rbac.core.api.service.PermissionsService;
import org.simplepoint.plugin.rbac.menu.api.repository.MenuRepository;
import org.simplepoint.plugin.rbac.menu.api.repository.RemoteModuleRepository;
import org.simplepoint.plugin.rbac.menu.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Feature;
import org.simplepoint.plugin.rbac.tenant.api.pojo.dto.FeaturePermissionsRelevanceDto;
import org.simplepoint.plugin.rbac.tenant.api.repository.ApplicationFeatureRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeaturePermissionRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.FeatureRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.FeatureService;
import org.simplepoint.plugin.rbac.tenant.api.service.PermissionVersionRefreshService;
import org.simplepoint.security.entity.Menu;
import org.simplepoint.security.entity.MicroModule;
import org.simplepoint.security.entity.Permissions;
import org.simplepoint.security.pojo.dto.MenuFeaturesRelevanceDto;
import org.simplepoint.security.service.MenuService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers RBAC-facing plugin manifest contributions.
 */
@Service
public class PluginRbacContributionHandler
    implements PluginLifecycleHandler, PluginInstallValidator, PluginInstallBatchValidator {

  private static final int ORDER = 100;

  private final RemoteModuleRepository remoteModuleRepository;
  private final MenuRepository menuRepository;
  private final PermissionsRepository permissionsRepository;
  private final FeatureRepository featureRepository;
  private final FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository;
  private final ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository;
  private final MicroAppService microAppService;
  private final MenuService menuService;
  private final PermissionsService permissionsService;
  private final FeatureService featureService;
  private final PermissionVersionRefreshService permissionVersionRefreshService;

  /**
   * Plugin RBAC contribution handler.
   */
  public PluginRbacContributionHandler(
      RemoteModuleRepository remoteModuleRepository,
      MenuRepository menuRepository,
      PermissionsRepository permissionsRepository,
      FeatureRepository featureRepository,
      FeaturePermissionRelevanceRepository featurePermissionRelevanceRepository,
      ApplicationFeatureRelevanceRepository applicationFeatureRelevanceRepository,
      MicroAppService microAppService,
      MenuService menuService,
      PermissionsService permissionsService,
      FeatureService featureService,
      PermissionVersionRefreshService permissionVersionRefreshService
  ) {
    this.remoteModuleRepository = remoteModuleRepository;
    this.menuRepository = menuRepository;
    this.permissionsRepository = permissionsRepository;
    this.featureRepository = featureRepository;
    this.featurePermissionRelevanceRepository = featurePermissionRelevanceRepository;
    this.applicationFeatureRelevanceRepository = applicationFeatureRelevanceRepository;
    this.microAppService = microAppService;
    this.menuService = menuService;
    this.permissionsService = permissionsService;
    this.featureService = featureService;
    this.permissionVersionRefreshService = permissionVersionRefreshService;
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
    return !remotes(manifest).isEmpty()
        || !safeList(manifest.getMenus()).isEmpty()
        || !safeList(manifest.getPermissions()).isEmpty()
        || !safeList(manifest.getFeatures()).isEmpty();
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
    final Set<String> declaredPermissions = declaredPermissionAuthorities(manifest);
    final Set<String> declaredFeatures = declaredFeatureCodes(manifest);
    validateRemotes(pluginId, manifest);
    validatePermissions(pluginId, manifest);
    validateFeatures(pluginId, manifest, declaredPermissions);
    validateMenus(pluginId, manifest, declaredFeatures);
  }

  @Override
  public void validate(List<Plugin> plugins) {
    if (!supports(plugins)) {
      return;
    }
    List<Plugin> candidates = plugins.stream()
        .filter(this::supports)
        .toList();
    BatchContributionIndex index = batchContributionIndex(candidates);
    for (Plugin candidate : candidates) {
      validateBatchCandidate(candidate, index);
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void installed(Plugin plugin) {
    PluginManifest manifest = plugin.manifest();
    String pluginId = requireText(manifest.getId(), "Plugin id is required");
    try {
      registerRemotes(plugin, pluginId, manifest);
      registerPermissions(pluginId, manifest);
      registerFeatures(pluginId, manifest);
      registerMenus(pluginId, manifest);
    } catch (RuntimeException failure) {
      cleanupInstalledContributions(pluginId, failure);
      throw failure;
    }
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void uninstalling(Plugin plugin) {
    String pluginId = requireText(plugin.manifest().getId(), "Plugin id is required");
    removePluginMenus(pluginId);
    removePluginFeatures(pluginId);
    removePluginPermissions(pluginId);
    removePluginRemotes(pluginId);
  }

  private void cleanupInstalledContributions(String pluginId, RuntimeException failure) {
    try {
      removePluginMenus(pluginId);
      removePluginFeatures(pluginId);
      removePluginPermissions(pluginId);
      removePluginRemotes(pluginId);
    } catch (RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private void validateBatchCandidate(Plugin plugin, BatchContributionIndex index) {
    PluginManifest manifest = plugin.manifest();
    String pluginId = requireText(manifest.getId(), "Plugin id is required");
    validateRemotes(pluginId, manifest);
    validatePermissions(pluginId, manifest);

    Set<String> availablePermissions = new LinkedHashSet<>(declaredPermissionAuthorities(manifest));
    for (String dependencyId : dependencyIds(manifest)) {
      availablePermissions.addAll(index.permissionsByPlugin().getOrDefault(dependencyId, Set.of()));
    }
    Set<String> unresolvedPermissions = referencedPermissions(manifest);
    unresolvedPermissions.removeAll(availablePermissions);
    availablePermissions.addAll(existingPermissionAuthorities(unresolvedPermissions));
    validateFeatureReferences(pluginId, manifest, availablePermissions);

    Set<String> availableFeatures = new LinkedHashSet<>(declaredFeatureCodes(manifest));
    List<Menu> dependencyMenus = new ArrayList<>();
    for (String dependencyId : dependencyIds(manifest)) {
      availableFeatures.addAll(index.featuresByPlugin().getOrDefault(dependencyId, Set.of()));
      dependencyMenus.addAll(index.menusByPlugin().getOrDefault(dependencyId, List.of()));
    }
    Set<String> unresolvedFeatures = referencedFeatures(manifest);
    unresolvedFeatures.removeAll(availableFeatures);
    availableFeatures.addAll(existingFeatureCodes(unresolvedFeatures));
    validateMenuReferences(pluginId, manifest, availableFeatures, dependencyMenus);
  }

  private void validateRemotes(String pluginId, PluginManifest manifest) {
    for (PluginManifest.RemoteContribution contribution : remotes(manifest)) {
      resolveRemoteContribution(pluginId, contribution);
    }
  }

  private void validatePermissions(String pluginId, PluginManifest manifest) {
    for (PluginManifest.PermissionContribution contribution : safeList(manifest.getPermissions())) {
      resolvePermissionContribution(pluginId, contribution);
    }
  }

  private void validateFeatures(
      String pluginId,
      PluginManifest manifest,
      Set<String> declaredPermissions
  ) {
    Set<String> availablePermissions = new LinkedHashSet<>(declaredPermissions);
    Set<String> unresolvedPermissions = referencedPermissions(manifest);
    unresolvedPermissions.removeAll(availablePermissions);
    availablePermissions.addAll(existingPermissionAuthorities(unresolvedPermissions));
    validateFeatureReferences(pluginId, manifest, availablePermissions);
  }

  private void validateFeatureReferences(
      String pluginId,
      PluginManifest manifest,
      Set<String> availablePermissions
  ) {
    for (PluginManifest.FeatureContribution contribution : safeList(manifest.getFeatures())) {
      FeatureContributionContext context = resolveFeatureContribution(pluginId, contribution);
      Set<String> missing = new LinkedHashSet<>(normalize(contribution.getPermissions()));
      missing.removeAll(availablePermissions);
      if (!missing.isEmpty()) {
        throw new IllegalStateException("Feature '" + context.code() + "' references missing permissions: "
            + String.join(",", missing));
      }
    }
  }

  private void validateMenus(String pluginId, PluginManifest manifest, Set<String> declaredFeatures) {
    Set<String> availableFeatures = new LinkedHashSet<>(declaredFeatures);
    Set<String> unresolvedFeatures = referencedFeatures(manifest);
    unresolvedFeatures.removeAll(availableFeatures);
    availableFeatures.addAll(existingFeatureCodes(unresolvedFeatures));
    validateMenuReferences(pluginId, manifest, availableFeatures, List.of());
  }

  private void validateMenuReferences(
      String pluginId,
      PluginManifest manifest,
      Set<String> availableFeatures,
      List<Menu> dependencyMenus
  ) {
    List<PluginManifest.MenuContribution> pending = new ArrayList<>(safeList(manifest.getMenus()));
    if (pending.isEmpty()) {
      return;
    }
    Map<String, Menu> menusByAuthority = new HashMap<>();
    Map<String, Menu> menusByPath = new HashMap<>();
    loadMenuIndexes(menusByAuthority, menusByPath);
    for (Menu menu : dependencyMenus) {
      indexMenu(menu, menusByAuthority, menusByPath);
    }
    while (!pending.isEmpty()) {
      boolean progressed = false;
      Iterator<PluginManifest.MenuContribution> iterator = pending.iterator();
      while (iterator.hasNext()) {
        PluginManifest.MenuContribution contribution = iterator.next();
        String parentId = resolveParentId(contribution.getParent(), menusByAuthority, menusByPath);
        if (hasText(contribution.getParent()) && !hasText(parentId)) {
          continue;
        }
        MenuContributionContext context = resolveMenuContribution(pluginId, contribution, menusByAuthority, menusByPath);
        Set<String> missing = new LinkedHashSet<>(normalize(contribution.getFeatureCodes()));
        missing.removeAll(availableFeatures);
        if (!missing.isEmpty()) {
          throw new IllegalStateException("Menu '" + context.authority() + "' references missing features: "
              + String.join(",", missing));
        }
        indexMenu(simulatedMenu(pluginId, context), menusByAuthority, menusByPath);
        iterator.remove();
        progressed = true;
      }
      if (!progressed) {
        throw new IllegalStateException("Unable to resolve plugin menu parent references: "
            + pending.stream().map(PluginManifest.MenuContribution::getAuthority).collect(Collectors.joining(",")));
      }
    }
  }

  private RemoteContributionContext resolveRemoteContribution(
      String pluginId,
      PluginManifest.RemoteContribution contribution
  ) {
    String serviceName = requireText(contribution.getName(), "Frontend remote name is required");
    MicroModule existing = findOne(remoteModuleRepository.findAll(Map.of("serviceName", serviceName)),
        "frontend remote", serviceName);
    MicroModule entryOwner = findOne(remoteModuleRepository.findAll(Map.of("entry", contribution.getEntry())),
        "frontend remote entry", contribution.getEntry());
    if (existing == null && entryOwner != null) {
      throw new IllegalStateException(
          "Frontend remote entry '" + contribution.getEntry() + "' is already used by "
              + entryOwner.getServiceName());
    }
    if (existing != null && entryOwner != null && !Objects.equals(existing.getId(), entryOwner.getId())) {
      throw new IllegalStateException(
          "Frontend remote '" + serviceName + "' conflicts with entry " + contribution.getEntry());
    }
    assertOwnedByPlugin(pluginId, existing, MicroModule::getPluginId, "frontend remote", serviceName);
    return new RemoteContributionContext(serviceName, existing);
  }

  private PermissionContributionContext resolvePermissionContribution(
      String pluginId,
      PluginManifest.PermissionContribution contribution
  ) {
    String authority = requireText(contribution.getAuthority(), "Permission authority is required");
    Permissions existing = findOne(permissionsRepository.findAll(Map.of("authority", authority)),
        "permission", authority);
    assertOwnedByPlugin(pluginId, existing, Permissions::getPluginId, "permission", authority);
    return new PermissionContributionContext(authority, existing);
  }

  private FeatureContributionContext resolveFeatureContribution(
      String pluginId,
      PluginManifest.FeatureContribution contribution
  ) {
    String code = requireText(contribution.getCode(), "Feature code is required");
    Feature existing = findOne(featureRepository.findAllByCodes(Set.of(code)), "feature", code);
    assertOwnedByPlugin(pluginId, existing, Feature::getPluginId, "feature", code);
    return new FeatureContributionContext(code, existing);
  }

  private MenuContributionContext resolveMenuContribution(
      String pluginId,
      PluginManifest.MenuContribution contribution,
      Map<String, Menu> menusByAuthority,
      Map<String, Menu> menusByPath
  ) {
    String authority = requireText(contribution.getAuthority(), "Menu authority is required");
    String path = requireText(contribution.getPath(), "Menu path is required for " + authority);
    Menu existing = menusByAuthority.get(authority);
    Menu existingByPath = menusByPath.get(path);
    if (existing == null && existingByPath != null) {
      throw new IllegalStateException("Menu path '" + path + "' is already used by " + existingByPath.getAuthority());
    }
    if (existing != null && existingByPath != null && !Objects.equals(existing.getId(), existingByPath.getId())) {
      throw new IllegalStateException("Menu '" + authority + "' conflicts with path " + path);
    }
    assertOwnedByPlugin(pluginId, existing, Menu::getPluginId, "menu", authority);
    return new MenuContributionContext(authority, path, existing);
  }

  private Menu simulatedMenu(String pluginId, MenuContributionContext context) {
    Menu menu = new Menu();
    menu.setId(firstNonBlank(
        context.existing() == null ? null : context.existing().getId(),
        context.authority(),
        context.path()));
    menu.setAuthority(context.authority());
    menu.setPath(context.path());
    menu.setPluginId(pluginId);
    return menu;
  }

  private Menu simulatedMenu(String pluginId, String authority, String path) {
    Menu menu = new Menu();
    menu.setId(firstNonBlank(authority, path));
    menu.setAuthority(authority);
    menu.setPath(path);
    menu.setPluginId(pluginId);
    return menu;
  }

  private void registerRemotes(Plugin plugin, String pluginId, PluginManifest manifest) {
    for (PluginManifest.RemoteContribution contribution : remotes(manifest)) {
      RemoteContributionContext context = resolveRemoteContribution(pluginId, contribution);
      MicroModule remote = context.existing() == null ? new MicroModule() : context.existing();
      remote.setDisplayName(firstNonBlank(contribution.getModule(), contribution.getName()));
      remote.setServiceName(context.serviceName());
      remote.setEntry(requireText(contribution.getEntry(), "Frontend remote entry is required"));
      remote.setDescription(firstNonBlank(contribution.getVersion(), manifest.getDescription()));
      remote.setPluginId(pluginId);
      remote.setPluginVersion(trimToNull(manifest.getVersion()));
      remote.setRemoteVersion(trimToNull(contribution.getVersion()));
      remote.setPluginArtifactSha256(plugin.artifact().sha256());
      saveRemote(remote);
    }
  }

  private void registerPermissions(String pluginId, PluginManifest manifest) {
    for (PluginManifest.PermissionContribution contribution : safeList(manifest.getPermissions())) {
      PermissionContributionContext context = resolvePermissionContribution(pluginId, contribution);
      Permissions permission = context.existing() == null ? new Permissions() : context.existing();
      permission.setAuthority(context.authority());
      permission.setName(requireText(contribution.getName(), "Permission name is required for " + context.authority()));
      permission.setResource(firstNonBlank(contribution.getResource(), context.authority()));
      permission.setDescription(firstNonBlank(contribution.getDescription(), contribution.getName(), context.authority()));
      permission.setType(contribution.getType() == null ? Permissions.OPERATION_TYPE : contribution.getType());
      permission.setPluginId(pluginId);
      savePermission(permission);
    }
  }

  private void registerFeatures(String pluginId, PluginManifest manifest) {
    Map<String, Set<String>> featurePermissions = new LinkedHashMap<>();
    for (PluginManifest.FeatureContribution contribution : safeList(manifest.getFeatures())) {
      FeatureContributionContext context = resolveFeatureContribution(pluginId, contribution);
      Feature feature = context.existing() == null ? new Feature() : context.existing();
      feature.setCode(context.code());
      feature.setName(requireText(contribution.getName(), "Feature name is required for " + context.code()));
      feature.setDescription(contribution.getDescription());
      feature.setSort(contribution.getSort());
      feature.setPublicAccess(Boolean.TRUE.equals(contribution.getPublicAccess()));
      feature.setRequireOrgTenant(Boolean.TRUE.equals(contribution.getRequireOrgTenant()));
      feature.setPluginId(pluginId);
      saveFeature(feature);

      Set<String> permissions = normalize(contribution.getPermissions());
      if (!permissions.isEmpty()) {
        featurePermissions.put(context.code(), permissions);
      }
    }

    for (Map.Entry<String, Set<String>> entry : featurePermissions.entrySet()) {
      authorizeFeaturePermissions(entry.getKey(), entry.getValue());
    }
  }

  private void registerMenus(String pluginId, PluginManifest manifest) {
    List<PluginManifest.MenuContribution> pending = new ArrayList<>(safeList(manifest.getMenus()));
    if (pending.isEmpty()) {
      return;
    }

    Map<String, Menu> menusByAuthority = new HashMap<>();
    Map<String, Menu> menusByPath = new HashMap<>();
    loadMenuIndexes(menusByAuthority, menusByPath);

    while (!pending.isEmpty()) {
      boolean progressed = false;
      Iterator<PluginManifest.MenuContribution> iterator = pending.iterator();
      while (iterator.hasNext()) {
        PluginManifest.MenuContribution contribution = iterator.next();
        String parentId = resolveParentId(contribution.getParent(), menusByAuthority, menusByPath);
        if (hasText(contribution.getParent()) && !hasText(parentId)) {
          continue;
        }
        Menu saved = upsertMenu(pluginId, contribution, parentId, menusByAuthority, menusByPath);
        indexMenu(saved, menusByAuthority, menusByPath);
        authorizeMenuFeatures(saved.getId(), normalize(contribution.getFeatureCodes()));
        iterator.remove();
        progressed = true;
      }
      if (!progressed) {
        throw new IllegalStateException("Unable to resolve plugin menu parent references: "
            + pending.stream().map(PluginManifest.MenuContribution::getAuthority).collect(Collectors.joining(",")));
      }
    }
  }

  private Menu upsertMenu(
      String pluginId,
      PluginManifest.MenuContribution contribution,
      String parentId,
      Map<String, Menu> menusByAuthority,
      Map<String, Menu> menusByPath
  ) {
    MenuContributionContext context = resolveMenuContribution(pluginId, contribution, menusByAuthority, menusByPath);
    Menu menu = context.existing() == null ? new Menu() : context.existing();
    menu.setAuthority(context.authority());
    menu.setTitle(requireText(contribution.getTitle(), "Menu title is required for " + context.authority()));
    menu.setLabel(firstNonBlank(contribution.getLabel(), contribution.getTitle()));
    menu.setPath(context.path());
    menu.setComponent(trimToNull(contribution.getComponent()));
    menu.setParent(parentId);
    menu.setIcon(trimToNull(contribution.getIcon()));
    menu.setSort(contribution.getSort());
    menu.setType(firstNonBlank(contribution.getType(), "item"));
    menu.setDanger(contribution.getDanger());
    menu.setDisabled(Boolean.TRUE.equals(contribution.getDisabled()));
    menu.setPluginId(pluginId);
    return menuService.initializeMenu(menu);
  }

  private void authorizeFeaturePermissions(String featureCode, Set<String> permissions) {
    if (permissions.isEmpty()) {
      return;
    }
    Set<String> existingPermissions = existingPermissionAuthorities(permissions);
    if (existingPermissions.size() != permissions.size()) {
      Set<String> missing = new LinkedHashSet<>(permissions);
      missing.removeAll(existingPermissions);
      throw new IllegalStateException("Feature '" + featureCode + "' references missing permissions: "
          + String.join(",", missing));
    }
    Set<String> missing = new LinkedHashSet<>(permissions);
    missing.removeAll(new HashSet<>(featureService.authorizedPermissions(featureCode)));
    if (missing.isEmpty()) {
      return;
    }
    FeaturePermissionsRelevanceDto dto = new FeaturePermissionsRelevanceDto();
    dto.setFeatureCode(featureCode);
    dto.setPermissionAuthority(missing);
    featureService.initializePermissions(dto);
  }

  private void authorizeMenuFeatures(String menuId, Set<String> featureCodes) {
    if (featureCodes.isEmpty()) {
      return;
    }
    Set<String> missing = new LinkedHashSet<>(featureCodes);
    missing.removeAll(new HashSet<>(menuService.authorized(menuId)));
    if (!missing.isEmpty()) {
      menuService.authorize(new MenuFeaturesRelevanceDto(menuId, missing));
    }
  }

  private void removePluginMenus(String pluginId) {
    Set<String> menuIds = menuRepository.findAll(Map.of("pluginId", pluginId)).stream()
        .map(Menu::getId)
        .filter(PluginRbacContributionHandler::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!menuIds.isEmpty()) {
      menuService.removeByIds(menuIds);
    }
  }

  private void removePluginFeatures(String pluginId) {
    List<Feature> features = featureRepository.findAll(Map.of("pluginId", pluginId));
    Set<String> featureIds = features.stream()
        .map(Feature::getId)
        .filter(PluginRbacContributionHandler::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Set<String> featureCodes = features.stream()
        .map(Feature::getCode)
        .filter(PluginRbacContributionHandler::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (featureCodes.isEmpty() && featureIds.isEmpty()) {
      return;
    }
    if (!featureCodes.isEmpty()) {
      featurePermissionRelevanceRepository.deleteAllByFeatureCodes(featureCodes);
      applicationFeatureRelevanceRepository.deleteAllByFeatureCodes(featureCodes);
    }
    if (!featureIds.isEmpty()) {
      featureRepository.deleteByIds(featureIds);
    }
    if (!featureCodes.isEmpty()) {
      permissionVersionRefreshService.refreshByFeatureCodes(featureCodes);
    }
  }

  private void removePluginPermissions(String pluginId) {
    Set<String> permissionIds = permissionsRepository.findAll(Map.of("pluginId", pluginId)).stream()
        .map(Permissions::getId)
        .filter(PluginRbacContributionHandler::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!permissionIds.isEmpty()) {
      permissionsService.removeByIds(permissionIds);
    }
  }

  private void removePluginRemotes(String pluginId) {
    Set<String> remoteIds = remoteModuleRepository.findAll(Map.of("pluginId", pluginId)).stream()
        .map(MicroModule::getId)
        .filter(PluginRbacContributionHandler::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!remoteIds.isEmpty()) {
      microAppService.removeByIds(remoteIds);
    }
  }

  private void saveRemote(MicroModule remote) {
    if (hasText(remote.getId())) {
      remoteModuleRepository.updateById(remote);
    } else {
      remoteModuleRepository.save(remote);
    }
  }

  private void savePermission(Permissions permission) {
    if (hasText(permission.getId())) {
      permissionsRepository.updateById(permission);
    } else {
      permissionsRepository.save(permission);
    }
  }

  private void saveFeature(Feature feature) {
    if (hasText(feature.getId())) {
      featureRepository.updateById(feature);
    } else {
      featureRepository.save(feature);
    }
  }

  private Set<String> existingPermissionAuthorities(Set<String> authorities) {
    if (authorities.isEmpty()) {
      return Set.of();
    }
    return permissionsRepository.findAll(Map.of("authority", "in:" + String.join(",", authorities))).stream()
        .map(Permissions::getAuthority)
        .filter(PluginRbacContributionHandler::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<String> existingFeatureCodes(Set<String> featureCodes) {
    if (featureCodes.isEmpty()) {
      return Set.of();
    }
    return featureRepository.findAllByCodes(featureCodes).stream()
        .map(Feature::getCode)
        .filter(PluginRbacContributionHandler::hasText)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private BatchContributionIndex batchContributionIndex(List<Plugin> plugins) {
    Map<String, String> remoteNames = new LinkedHashMap<>();
    Map<String, String> remoteEntries = new LinkedHashMap<>();
    Map<String, String> permissionAuthorities = new LinkedHashMap<>();
    Map<String, String> featureCodes = new LinkedHashMap<>();
    Map<String, String> menuAuthorities = new LinkedHashMap<>();
    Map<String, String> menuPaths = new LinkedHashMap<>();
    Map<String, Set<String>> permissionsByPlugin = new LinkedHashMap<>();
    Map<String, Set<String>> featuresByPlugin = new LinkedHashMap<>();
    Map<String, List<Menu>> menusByPlugin = new LinkedHashMap<>();

    for (Plugin plugin : plugins) {
      PluginManifest manifest = plugin.manifest();
      String pluginId = requireText(manifest.getId(), "Plugin id is required");
      for (PluginManifest.RemoteContribution contribution : remotes(manifest)) {
        claim(remoteNames, trimToNull(contribution.getName()), pluginId, "frontend remote");
        claim(remoteEntries, trimToNull(contribution.getEntry()), pluginId, "frontend remote entry");
      }

      Set<String> permissions = declaredPermissionAuthorities(manifest);
      permissions.forEach(authority -> claim(permissionAuthorities, authority, pluginId, "permission"));
      permissionsByPlugin.put(pluginId, permissions);

      Set<String> features = declaredFeatureCodes(manifest);
      features.forEach(code -> claim(featureCodes, code, pluginId, "feature"));
      featuresByPlugin.put(pluginId, features);

      List<Menu> menus = new ArrayList<>();
      for (PluginManifest.MenuContribution contribution : safeList(manifest.getMenus())) {
        String authority = trimToNull(contribution.getAuthority());
        String path = trimToNull(contribution.getPath());
        claim(menuAuthorities, authority, pluginId, "menu");
        claim(menuPaths, path, pluginId, "menu path");
        if (authority != null || path != null) {
          menus.add(simulatedMenu(pluginId, authority, path));
        }
      }
      menusByPlugin.put(pluginId, menus);
    }
    return new BatchContributionIndex(permissionsByPlugin, featuresByPlugin, menusByPlugin);
  }

  private void claim(Map<String, String> claims, String key, String pluginId, String type) {
    if (key == null) {
      return;
    }
    String previous = claims.putIfAbsent(key, pluginId);
    if (previous == null) {
      return;
    }
    if (previous.equals(pluginId)) {
      throw new IllegalStateException(type + " '" + key + "' is declared more than once by plugin " + pluginId);
    }
    throw new IllegalStateException(
        type + " '" + key + "' is declared by multiple plugin candidates: " + previous + ", " + pluginId);
  }

  private Set<String> declaredPermissionAuthorities(PluginManifest manifest) {
    return safeList(manifest.getPermissions()).stream()
        .map(PluginManifest.PermissionContribution::getAuthority)
        .map(PluginRbacContributionHandler::trimToNull)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<String> declaredFeatureCodes(PluginManifest manifest) {
    return safeList(manifest.getFeatures()).stream()
        .map(PluginManifest.FeatureContribution::getCode)
        .map(PluginRbacContributionHandler::trimToNull)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<String> referencedPermissions(PluginManifest manifest) {
    return safeList(manifest.getFeatures()).stream()
        .map(PluginManifest.FeatureContribution::getPermissions)
        .filter(Objects::nonNull)
        .flatMap(Collection::stream)
        .map(PluginRbacContributionHandler::trimToNull)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<String> referencedFeatures(PluginManifest manifest) {
    return safeList(manifest.getMenus()).stream()
        .map(PluginManifest.MenuContribution::getFeatureCodes)
        .filter(Objects::nonNull)
        .flatMap(Collection::stream)
        .map(PluginRbacContributionHandler::trimToNull)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<String> dependencyIds(PluginManifest manifest) {
    return safeList(manifest.getDependencies()).stream()
        .map(PluginManifest.PluginDependency::getId)
        .map(PluginRbacContributionHandler::trimToNull)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private void loadMenuIndexes(Map<String, Menu> menusByAuthority, Map<String, Menu> menusByPath) {
    for (Menu menu : menuRepository.findAll(Map.of())) {
      indexMenu(menu, menusByAuthority, menusByPath);
    }
  }

  private void indexMenu(Menu menu, Map<String, Menu> menusByAuthority, Map<String, Menu> menusByPath) {
    if (menu == null) {
      return;
    }
    if (hasText(menu.getAuthority())) {
      menusByAuthority.put(menu.getAuthority(), menu);
    }
    if (hasText(menu.getPath())) {
      menusByPath.put(menu.getPath(), menu);
    }
  }

  private String resolveParentId(String parentRef, Map<String, Menu> menusByAuthority, Map<String, Menu> menusByPath) {
    String ref = trimToNull(parentRef);
    if (ref == null) {
      return null;
    }
    Menu parent = menusByAuthority.get(ref);
    if (parent == null) {
      parent = menusByPath.get(ref);
    }
    return parent == null ? null : parent.getId();
  }

  private List<PluginManifest.RemoteContribution> remotes(PluginManifest manifest) {
    if (manifest.getFrontend() == null) {
      return List.of();
    }
    return safeList(manifest.getFrontend().getRemotes());
  }

  private static <T> List<T> safeList(List<T> items) {
    return items == null ? List.of() : items;
  }

  private static Set<String> normalize(Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return Set.of();
    }
    return values.stream()
        .map(PluginRbacContributionHandler::trimToNull)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private static <T> T findOne(Collection<T> items, String type, String key) {
    if (items == null || items.isEmpty()) {
      return null;
    }
    if (items.size() > 1) {
      throw new IllegalStateException(type + " '" + key + "' matched multiple rows");
    }
    return items.iterator().next();
  }

  private static <T> void assertOwnedByPlugin(
      String pluginId,
      T existing,
      java.util.function.Function<T, String> pluginIdGetter,
      String type,
      String key
  ) {
    if (existing == null) {
      return;
    }
    String owner = pluginIdGetter.apply(existing);
    if (!pluginId.equals(owner)) {
      throw new IllegalStateException(type + " '" + key + "' is not owned by plugin " + pluginId);
    }
  }

  private static String requireText(String value, String message) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      throw new IllegalArgumentException(message);
    }
    return trimmed;
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String trimmed = trimToNull(value);
      if (trimmed != null) {
        return trimmed;
      }
    }
    return null;
  }

  private static boolean hasText(String value) {
    return trimToNull(value) != null;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private record RemoteContributionContext(String serviceName, MicroModule existing) {
  }

  private record PermissionContributionContext(String authority, Permissions existing) {
  }

  private record FeatureContributionContext(String code, Feature existing) {
  }

  private record MenuContributionContext(String authority, String path, Menu existing) {
  }

  private record BatchContributionIndex(
      Map<String, Set<String>> permissionsByPlugin,
      Map<String, Set<String>> featuresByPlugin,
      Map<String, List<Menu>> menusByPlugin
  ) {
  }
}
