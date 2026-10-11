package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationScopeType;
import org.simplepoint.core.RequestContextHolder;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.service.support.ButtonResourceMetadataRegistry;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResource;
import org.simplepoint.plugin.rbac.tenant.api.entity.TenantResourceMode;
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
  private RoleResourceGrantRepository grantRepository;
  private ResourceAuthorizationVersionService authorizationVersionService;
  private ResourceServiceImpl service;

  @BeforeEach
  void setUp() {
    repository = mock(ResourceRepository.class);
    ancestorRepository = mock(ResourceAncestorRepository.class);
    grantRepository = mock(RoleResourceGrantRepository.class);
    authorizationVersionService = mock(ResourceAuthorizationVersionService.class);
    service = new ResourceServiceImpl(
        repository,
        mock(DetailsProviderService.class),
        ancestorRepository,
        mock(MicroAppService.class),
        emptyProvider(),
        emptyProvider(),
        provider(grantRepository),
        provider(authorizationVersionService),
        emptyProvider(),
        emptyProvider()
    );
  }

  @Test
  void tenantCustomRoutesUseTenantCatalogAndPreserveItsHierarchy() {
    TenantResourceCatalog catalog = mock(TenantResourceCatalog.class);
    service.setTenantResourceCatalog(catalog);
    TenantResource directory = new TenantResource();
    directory.setTenantId("tenant-a"); directory.setId("directory");
    directory.setCode("tenant.tenant-a.directory"); directory.setName("Workspace");
    directory.setType(ResourceType.GROUP); directory.setEnabled(true);
    TenantResource page = new TenantResource();
    page.setTenantId("tenant-a"); page.setId("platform-page-id");
    page.setPlatformResourceId("platform-page-id"); page.setParentId("directory");
    page.setCode("licensed.page"); page.setName("Licensed page");
    page.setType(ResourceType.PAGE); page.setPath("/licensed");
    page.setComponent("common/LicensedPage"); page.setEnabled(true); page.setGrantable(true);
    when(catalog.mode("tenant-a")).thenReturn(TenantResourceMode.CUSTOM);
    when(catalog.navigationResources(eq("tenant-a"), anyCollection())).thenReturn(List.of(directory, page));
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
    page.setTenantId("tenant-a"); page.setId("platform-page-id");
    page.setPlatformResourceId("platform-page-id");
    page.setCode("licensed.page"); page.setName("Licensed page");
    page.setType(ResourceType.PAGE); page.setPath("/licensed");
    page.setComponent("common/LicensedPage"); page.setEnabled(true); page.setGrantable(true);
    TenantResource alias = new TenantResource();
    alias.setTenantId("tenant-a"); alias.setId("alias-id");
    alias.setTargetResourceId(page.getId());
    alias.setCode("tenant.tenant-a.alias-id"); alias.setName("Custom page");
    alias.setType(ResourceType.PAGE); alias.setPath("/custom-licensed");
    alias.setComponent(page.getComponent()); alias.setEnabled(true); alias.setGrantable(true);
    when(catalog.mode("tenant-a")).thenReturn(TenantResourceMode.CUSTOM);
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
  void syncRetiresResourcesNoLongerDeclaredByTheOwner() {
    Resource current = resource("current-id", "dna.federation.catalogs.create");
    current.setPermissionMetadata("{\"models\":[]}");
    Resource stale = resource("stale-id", "dna.federation.data-catalogs.create");
    when(repository.loadAll()).thenReturn(List.of(current, stale));
    when(repository.updateById(any(Resource.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(repository.findAllByIds(Set.of("stale-id"))).thenReturn(List.of(stale));

    ResourceDeclaration declaration = new ResourceDeclaration();
    declaration.setCode(current.getCode());
    declaration.setName("Create catalog");
    declaration.setLabel("Create catalog");
    declaration.setType(ResourceType.ACTION);
    declaration.setScopeTypes(Set.of(ResourceScopeType.PLATFORM));

    service.sync("dna", Set.of(declaration));

    verify(repository).updateById(org.mockito.ArgumentMatchers.argThat((Resource value) ->
        "current-id".equals(value.getId()) && current.getPermissionMetadata().equals(value.getPermissionMetadata())));
    verify(repository).deleteByIds(List.of("stale-id"));
    verify(grantRepository).deleteAllByResourceCodes(Set.of(stale.getCode()));
    verify(authorizationVersionService).refreshByResourceCodes(Set.of(stale.getCode()));
  }

  @Test
  void limitTreeRejectsMissingAuthorizationWithoutFallingBackToUnscopedRepositories() {
    assertThatThrownBy(() -> service.limitTree(Map.of(), PageRequest.of(0, 20)))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(repository, ancestorRepository);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MASKED", "HIDDEN"})
  void administratorNavigationRequiresTheActualQueryAdapterInsteadOfAnUnscopedRepositoryFallback(String access) {
    var protectedContext = new AuthorizationContext(); protectedContext.setUserId("second"); protectedContext.setIsAdministrator(true);
    protectedContext.setScopeType(AuthorizationScopeType.PLATFORM);
    protectedContext.setFieldPermissions(Map.of(Resource.class.getName() + "#name", access));
    RequestContextHolder.setContext(RequestContextHolder.AUTHORIZATION_CONTEXT_KEY, protectedContext);
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
