package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceCatalogWriteBoundary;
import org.simplepoint.plugin.rbac.resource.service.support.ResourceRegistrationDeployments;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.service.TenantResourceCatalog;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceNode;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;

class ResourceServiceImplTest {

  private ResourceRepository repository;
  private ResourceAncestorRepository ancestorRepository;
  private ResourceServiceImpl service;

  private final org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects
      catalogEffects =
          mock(org.simplepoint.plugin.rbac.tenant.api.service.PlatformResourceCatalogEffects.class);

  @BeforeEach
  void setUp() {
    repository = mock(ResourceRepository.class);
    ancestorRepository = mock(ResourceAncestorRepository.class);
    service =
        new ResourceServiceImpl(
            repository,
            mock(DetailsProviderService.class),
            ancestorRepository,
            mock(MicroAppService.class),
            emptyProvider(),
            emptyProvider(),
            null,
            mock(ResourceCatalogWriteBoundary.class),
            mock(ResourceRegistrationDeployments.class),
            catalogEffects);
  }

  @Test
  void inheritedCrudRejectsTenantAndPersonalAdministratorsBeforePersistence() {
    for (var scope : List.of(AuthorizationScopeType.TENANT, AuthorizationScopeType.PERSONAL)) {
      var context = new AuthorizationContext();
      context.setUserId("tenant-owner");
      context.setScopeType(scope);
      context.setIsAdministrator(true);
      context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
      RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
      List<Runnable> calls =
          List.of(
              () -> service.schema(),
              () -> service.findById("platform"),
              () -> service.findAllByIds(Set.of("platform")),
              () -> service.findAll(Map.of()),
              () -> service.limit(Map.of(), PageRequest.of(0, 20)),
              () -> service.create(new Resource()),
              () -> service.create(List.of(new Resource())),
              () -> service.modifyById(new Resource()),
              () -> service.removeById("platform"),
              () -> service.removeByIds(Set.of("platform")),
              () -> service.removeAll());
      for (var call : calls) {
        assertThatThrownBy(call::run)
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
      }
    }
    verifyNoInteractions(repository);
  }

  @Test
  void platformCrudRequiresItsOwnActionWhileDistributionRequiresAdministrator() {
    var context = new AuthorizationContext();
    context.setUserId("platform-viewer");
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setResources(Set.of("resources.view"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);
    assertThatThrownBy(() -> service.create(new Resource()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> service.modifyById(new Resource()))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> service.removeAll())
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    assertThatThrownBy(() -> service.filterDistributableCodes(Set.of("resource")))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    PlatformResourceBoundary.write(platformEditor(), "resources.edit");
    verifyNoInteractions(repository);
  }

  private AuthorizationContext platformEditor() {
    var context = new AuthorizationContext();
    context.setUserId("platform-editor");
    context.setScopeType(AuthorizationScopeType.PLATFORM);
    context.setResources(Set.of("resources.edit"));
    return context;
  }

  @Test
  void tenantRoutesUseTenantCatalogAndPreserveItsHierarchy() {
    TenantResourceCatalog catalog = mock(TenantResourceCatalog.class);
    service.setTenantResourceCatalog(catalog);
    TenantResource directory = new TenantResource();
    directory.setTenantId("tenant-a");
    directory.setId("directory");
    directory.setCode("tenant.tenant-a.directory");
    directory.setName("Workspace");
    directory.setType(ResourceType.GROUP);
    directory.setEnabled(true);
    TenantResource page = new TenantResource();
    page.setTenantId("tenant-a");
    page.setId("platform-page-id");
    page.setPlatformResourceId("platform-page-id");
    page.setParentId("directory");
    page.setCode("licensed.page");
    page.setName("Licensed page");
    page.setType(ResourceType.PAGE);
    page.setPath("/licensed");
    page.setComponent("common/LicensedPage");
    page.setEnabled(true);
    page.setGrantable(true);
    when(catalog.navigationResources(eq("tenant-a"), anyCollection()))
        .thenReturn(List.of(directory, page));
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("verified-user");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setResources(Set.of("licensed.page"));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);

    var routes = service.routes();

    assertThat(routes.routes()).hasSize(1);
    ResourceNode root = routes.routes().iterator().next();
    assertThat(root.getName()).isEqualTo("Workspace");
    assertThat(root.getChildren()).hasSize(1);
    assertThat(root.getChildren().getFirst().getPath()).isEqualTo("/licensed");
  }

  @Test
  void tenantPageAliasReplacesItsPlatformPageInRoutes() {
    TenantResourceCatalog catalog = mock(TenantResourceCatalog.class);
    service.setTenantResourceCatalog(catalog);
    TenantResource page = new TenantResource();
    page.setTenantId("tenant-a");
    page.setId("platform-page-id");
    page.setPlatformResourceId("platform-page-id");
    page.setCode("licensed.page");
    page.setName("Licensed page");
    page.setType(ResourceType.PAGE);
    page.setPath("/licensed");
    page.setComponent("common/LicensedPage");
    page.setEnabled(true);
    page.setGrantable(true);
    TenantResource alias = new TenantResource();
    alias.setTenantId("tenant-a");
    alias.setId("alias-id");
    alias.setTargetResourceId(page.getId());
    alias.setCode("tenant.tenant-a.alias-id");
    alias.setName("Custom page");
    alias.setType(ResourceType.PAGE);
    alias.setPath("/custom-licensed");
    alias.setComponent(page.getComponent());
    alias.setEnabled(true);
    alias.setGrantable(true);
    when(catalog.navigationResources(eq("tenant-a"), anyCollection())).thenReturn(List.of(alias));
    AuthorizationContext context = new AuthorizationContext();
    context.setUserId("verified-user");
    context.setScopeType(AuthorizationScopeType.TENANT);
    context.setAttributes(Map.of("X-Tenant-Id", "tenant-a"));
    context.setResources(Set.of(page.getCode(), alias.getCode()));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, context);

    var routes = service.routes();

    assertThat(routes.routes()).hasSize(1);
    assertThat(routes.routes().iterator().next().getPath()).isEqualTo("/custom-licensed");
  }

  @AfterEach
  void clearContext() {
    RequestContextHolder.clearContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY);
  }

  @Test
  void completeSnapshotIsValidatedBeforeAnyResourceOrGrantMutation() {
    Resource foreign = resource("foreign-id", "foreign.view");
    foreign.setPluginId("foreign");
    when(repository.loadAll()).thenReturn(List.of(foreign));
    ResourceDeclaration first = new ResourceDeclaration();
    first.setCode("dna.new");
    ResourceDeclaration conflicting = new ResourceDeclaration();
    conflicting.setCode("foreign.view");
    var snapshot = new java.util.LinkedHashSet<ResourceDeclaration>();
    snapshot.add(first);
    snapshot.add(conflicting);

    assertThatThrownBy(() -> service.sync("dna", snapshot))
        .isInstanceOf(org.simplepoint.security.resource.ResourceRegistrationException.class);

    verify(repository, never()).save(any(Resource.class));
    verify(repository, never()).updateById(any(Resource.class));
    verify(repository, never()).deleteByIds(anyCollection());
    verifyNoInteractions(ancestorRepository, catalogEffects);
  }

  @Test
  void staleClosureCannotDeleteRetainedOrForeignRecords() {
    Resource current = resource("current-id", "dna.current");
    Resource stale = resource("stale-id", "dna.stale");
    when(repository.loadAll()).thenReturn(List.of(current, stale));
    when(repository.updateById(any(Resource.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(ancestorRepository.findChildIdsByAncestorIds(Set.of("stale-id")))
        .thenReturn(Set.of("current-id"));
    when(repository.loadByIds(Set.of("stale-id", "current-id")))
        .thenReturn(List.of(stale, current));
    ResourceDeclaration declaration = new ResourceDeclaration();
    declaration.setCode(current.getCode());

    assertThatThrownBy(() -> service.sync("dna", Set.of(declaration)))
        .isInstanceOf(org.simplepoint.security.resource.ResourceRegistrationException.class);

    verify(repository, never()).deleteByIds(anyCollection());
    verifyNoInteractions(catalogEffects);
  }

  @Test
  void syncRetiresResourcesNoLongerDeclaredByTheOwner() {
    Resource current = resource("current-id", "dna.federation.catalogs.create");
    current.setPermissionMetadata("{\"models\":[]}");
    Resource stale = resource("stale-id", "dna.federation.data-catalogs.create");
    when(repository.loadAll()).thenReturn(List.of(current, stale));
    when(repository.updateById(any(Resource.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.findAllByIds(Set.of("stale-id"))).thenReturn(List.of(stale));

    ResourceDeclaration declaration = new ResourceDeclaration();
    declaration.setCode(current.getCode());
    declaration.setName("Create catalog");
    declaration.setLabel("Create catalog");
    declaration.setType(ResourceType.ACTION);
    declaration.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));

    service.sync("dna", Set.of(declaration));

    verify(repository)
        .updateById(
            org.mockito.ArgumentMatchers.argThat(
                (Resource value) ->
                    "current-id".equals(value.getId())
                        && current.getPermissionMetadata().equals(value.getPermissionMetadata())));
    verify(repository).deleteByIds(List.of("stale-id"));
    verify(catalogEffects).retire(Set.of("stale-id"), Set.of(stale.getCode()));
    verify(catalogEffects).synchronize(List.of("current-id"), java.util.Map.of());
  }

  @Test
  void syncPersistsHierarchyWithoutMutatingCallerDeclarations() {
    Resource root = resource("root-id", "dna.root");
    Resource child = resource("child-id", "dna.child");
    when(repository.loadAll()).thenReturn(List.of(root, child));
    when(repository.updateById(any(Resource.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var childDeclaration = new ResourceDeclaration();
    childDeclaration.setCode(child.getCode());
    childDeclaration.setName("Child");
    childDeclaration.setType(ResourceType.ACTION);
    var rootDeclaration = new ResourceDeclaration();
    rootDeclaration.setCode(root.getCode());
    rootDeclaration.setName("Root");
    rootDeclaration.setType(ResourceType.PAGE);
    rootDeclaration.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
    rootDeclaration.setChildren(Set.of(childDeclaration));

    service.sync("dna", Set.of(rootDeclaration));

    assertThat(childDeclaration.getParentId()).isNull();
    verify(repository)
        .updateById(
            org.mockito.ArgumentMatchers.argThat(
                (Resource value) ->
                    "child-id".equals(value.getId())
                        && "root-id".equals(value.getParentId())
                        && value.getScopeTypes().equals(Set.of(ResourceScopeType.PLATFORM))));
  }

  @Test
  void declarationDistributionIncludesExplicitHostsAndPublicCapabilitiesWithoutStructuralNodes() {
    var provisioner =
        mock(org.simplepoint.plugin.rbac.tenant.api.service.BuiltInTenantProvisioner.class);
    final var registration =
        new ResourceServiceImpl(
            repository,
            mock(DetailsProviderService.class),
            ancestorRepository,
            mock(MicroAppService.class),
            emptyProvider(),
            provider(provisioner),
            null,
            mock(ResourceCatalogWriteBoundary.class),
            mock(ResourceRegistrationDeployments.class),
            catalogEffects);
    var group = resource("group", "app.group");
    group.setType(ResourceType.MODULE);
    group.setGrantable(false);
    var host = resource("host", "app.runtime.host");
    host.setType(ResourceType.PAGE);
    host.setGrantable(false);
    var publicEntry = resource("public", "app.public");
    publicEntry.setType(ResourceType.API);
    publicEntry.setGrantable(false);
    publicEntry.setPublicAccess(true);
    var action = resource("action", "app.action");
    var disabled = resource("disabled", "app.disabled");
    disabled.setDisabled(true);
    var entries = List.of(group, host, publicEntry, action, disabled);
    var declarations = new java.util.LinkedHashSet<ResourceDeclaration>();
    for (var row : entries) {
      row.setPluginId("app");
      row.setScopeTypes(Set.of(ResourceScopeType.TENANT));
      var declaration = new ResourceDeclaration();
      org.springframework.beans.BeanUtils.copyProperties(row, declaration);
      declarations.add(declaration);
    }
    when(repository.loadAll()).thenReturn(entries);
    when(repository.findAllByCodes(anyCollection())).thenReturn(entries);
    when(repository.updateById(any(Resource.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    registration.sync("app", declarations);
    verify(provisioner)
        .provisionApplicationResources(
            "app", Set.of("app.runtime.host", "app.public", "app.action"), Set.of());
    verify(catalogEffects)
        .synchronize(
            org.mockito.ArgumentMatchers.anyCollection(),
            org.mockito.ArgumentMatchers.eq(java.util.Map.of()));
  }

  @Test
  void limitTreeRejectsMissingAuthorizationWithoutFallingBackToUnscopedRepositories() {
    assertThatThrownBy(() -> service.limitTree(Map.of(), PageRequest.of(0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(repository, ancestorRepository);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void administratorNavigationRequiresTheActualQueryAdapterInsteadOfAnUnscopedRepositoryFallback(
      String access) {
    var protectedContext = new AuthorizationContext();
    protectedContext.setUserId("second");
    protectedContext.setIsAdministrator(true);
    protectedContext.setScopeType(AuthorizationScopeType.PLATFORM);
    protectedContext.setFieldPermissions(Map.of(Resource.class.getName() + "#name", access));
    RequestContextHolder.setContext(
        RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, protectedContext);
    assertThatThrownBy(() -> service.routes()).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(repository, ancestorRepository);
  }

  private Resource resource(String id, String code) {
    Resource resource = new Resource();
    resource.setId(id);
    resource.setCode(code);
    resource.setName(code);
    resource.setLabel(code);
    resource.setType(ResourceType.ACTION);
    resource.setPluginId("dna");
    resource.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));
    resource.setGrantable(Boolean.TRUE);
    resource.setPublicAccess(Boolean.FALSE);
    resource.setDisabled(Boolean.FALSE);
    return resource;
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> emptyProvider() {
    return mock(ObjectProvider.class);
  }
}
