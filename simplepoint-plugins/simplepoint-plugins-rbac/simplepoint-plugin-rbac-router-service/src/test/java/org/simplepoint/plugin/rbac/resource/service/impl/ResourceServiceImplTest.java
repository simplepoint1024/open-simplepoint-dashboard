package org.simplepoint.plugin.rbac.resource.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.rbac.core.api.repository.RoleResourceGrantRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceAncestorRepository;
import org.simplepoint.plugin.rbac.resource.api.repository.ResourceRepository;
import org.simplepoint.plugin.rbac.resource.api.service.MicroAppService;
import org.simplepoint.plugin.rbac.resource.service.support.ButtonResourceMetadataRegistry;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantPackageRelevanceRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.TenantRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.ResourceAuthorizationVersionService;
import org.simplepoint.security.ResourceDeclaration;
import org.simplepoint.security.entity.Resource;
import org.simplepoint.security.entity.ResourceNode;
import org.simplepoint.security.entity.ResourceScopeType;
import org.simplepoint.security.entity.ResourceType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
  void syncRetiresResourcesNoLongerDeclaredByTheOwner() {
    Resource current = resource("current-id", "dna.federation.catalogs.create");
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

    verify(repository).deleteByIds(List.of("stale-id"));
    verify(grantRepository).deleteAllByResourceCodes(Set.of(stale.getCode()));
    verify(authorizationVersionService).refreshByResourceCodes(Set.of(stale.getCode()));
  }

  @Test
  void limitTreeExposesTheDirectParentScopeBoundaryForEditing() {
    Resource parent = resource("parent-id", "resources.root");
    parent.setType(ResourceType.MODULE);
    parent.setScopeTypes(Set.of(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL));
    Resource child = resource("child-id", "resources.child");
    child.setParentId(parent.getId());
    child.setScopeTypes(Set.of(ResourceScopeType.TENANT));
    PageRequest pageable = PageRequest.of(0, 20);
    when(repository.limit(any(), any())).thenReturn(new PageImpl<>(List.of(parent), pageable, 1));
    when(ancestorRepository.findChildIdsByAncestorIds(List.of(parent.getId())))
        .thenReturn(List.of(child.getId()));
    when(repository.findAllByIds(List.of(child.getId()))).thenReturn(List.of(child));

    Page<ResourceNode> result = service.limitTree(Map.of(), pageable);

    ResourceNode childNode = result.getContent().getFirst().getChildren().getFirst();
    assertThat(childNode.getParentScopeTypes())
        .containsExactlyInAnyOrder(ResourceScopeType.TENANT, ResourceScopeType.PERSONAL);
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
