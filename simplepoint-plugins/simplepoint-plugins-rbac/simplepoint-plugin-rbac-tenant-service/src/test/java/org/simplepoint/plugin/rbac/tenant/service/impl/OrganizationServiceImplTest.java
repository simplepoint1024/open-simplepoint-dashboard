package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.AuthorizationContext;
import org.simplepoint.core.AuthorizationContextHolder;
import org.simplepoint.plugin.rbac.tenant.api.constants.TenantDictionaryCodes;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class OrganizationServiceImplTest {

  @Mock
  OrganizationRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  DictionaryItemRepository dictionaryItemRepository;

  @InjectMocks
  OrganizationServiceImpl service;

  // ── helpers ───────────────────────────────────────────────────────────────

  private AuthorizationContext ctxWithTenant(String tenantId) {
    AuthorizationContext ctx = new AuthorizationContext();
    ctx.setAttributes(Map.of("X-Tenant-Id", tenantId));
    return ctx;
  }

  private Organization org(String id, String name, String code, String type) {
    Organization o = new Organization();
    o.setId(id);
    o.setName(name);
    o.setCode(code);
    o.setType(type);
    return o;
  }

  // ── limit ─────────────────────────────────────────────────────────────────

  @Test
  void limit_returnsEmptyPageWhenNoTenantInContext() {
    Page<Organization> result = service.limit(null, Pageable.unpaged());
    assertThat(result.isEmpty()).isTrue();
  }

  @Test
  void limit_delegatesToSuperWhenTenantPresent() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));

      Page<Organization> page = Page.empty(Pageable.unpaged());
      when(repository.limit(any(), any())).thenReturn(page);

      Page<Organization> result = service.limit(Map.of(), Pageable.unpaged());

      assertThat(result).isSameAs(page);
    }
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void create_throwsWhenNoTenantInContext() {
    Organization org = org(null, "HQ", "HQ", "group");
    assertThatThrownBy(() -> service.create(org))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("请先选择租户");
  }

  @Test
  void create_throwsWhenNameIsBlank() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization org = org(null, "  ", "HQ", "group");
      assertThatThrownBy(() -> service.create(org))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("组织名称不能为空");
    }
  }

  @Test
  void create_throwsWhenCodeIsBlank() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization org = org(null, "HQ Org", "", "group");
      assertThatThrownBy(() -> service.create(org))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("组织编码不能为空");
    }
  }

  @Test
  void create_throwsWhenTypeIsInvalid() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      Organization org = org(null, "HQ Org", "HQ", "invalid_type");
      assertThatThrownBy(() -> service.create(org))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("组织类型无效");
    }
  }

  @Test
  void create_throwsWhenCodeAlreadyExists() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      when(repository.existsByTenantIdAndCode("t1", "HQ")).thenReturn(true);
      Organization org = org(null, "HQ Org", "HQ", "group");
      assertThatThrownBy(() -> service.create(org))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("当前租户下组织编码已存在");
    }
  }

  @Test
  void create_successWithNoParent() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      when(repository.existsByTenantIdAndCode("t1", "HQ")).thenReturn(false);
      when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());
      Organization org = org(null, "HQ Org", "HQ", "group");
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      Organization saved = service.create(org);

      assertThat(saved.getTenantId()).isEqualTo("t1");
      assertThat(saved.getEnabled()).isTrue();
      verify(repository).save(any());
    }
  }

  @Test
  void create_throwsWhenParentNotFound() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      when(repository.existsByTenantIdAndCode("t1", "HQ")).thenReturn(false);
      when(repository.findByIdAndTenantId("parent1", "t1")).thenReturn(Optional.empty());
      Organization org = org(null, "HQ Org", "HQ", "group");
      org.setParentId("parent1");
      assertThatThrownBy(() -> service.create(org))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("上级组织不存在");
    }
  }

  @Test
  void create_successWithValidParent() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      when(repository.existsByTenantIdAndCode("t1", "CHILD")).thenReturn(false);

      Organization parent = org("parent1", "Parent Org", "PARENT", "group");
      parent.setTenantId("t1");
      when(repository.findByIdAndTenantId("parent1", "t1")).thenReturn(Optional.of(parent));
      when(detailsProviderService.getDialects(any())).thenReturn(Collections.emptySet());
      when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

      Organization child = org(null, "Child Org", "CHILD", "group");
      child.setParentId("parent1");

      Organization saved = service.create(child);

      assertThat(saved.getTenantId()).isEqualTo("t1");
      verify(repository).save(any());
    }
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenNoTenantInContext() {
    Organization org = org("org1", "HQ Org", "HQ", "group");
    assertThatThrownBy(() -> service.modifyById(org))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("请先选择租户");
  }

  @Test
  void modifyById_throwsWhenOrgNotFound() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      when(repository.findByIdAndTenantId("org1", "t1")).thenReturn(Optional.empty());
      Organization org = org("org1", "HQ Org", "HQ", "group");
      assertThatThrownBy(() -> service.modifyById(org))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("组织机构不存在");
    }
  }

  @Test
  void modifyById_throwsWhenIdIsBlank() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization org = new Organization();
      org.setId("  ");
      assertThatThrownBy(() -> service.modifyById(org))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("组织机构标识不能为空");
    }
  }

  @Test
  void modifyById_throwsWhenCodeDuplicateForOtherOrg() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization current = org("org1", "Old Name", "HQ", "group");
      current.setTenantId("t1");
      when(repository.findByIdAndTenantId("org1", "t1")).thenReturn(Optional.of(current));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      when(repository.existsByTenantIdAndCodeAndIdNot("t1", "HQ", "org1")).thenReturn(true);

      Organization update = org("org1", "New Name", "HQ", "group");
      assertThatThrownBy(() -> service.modifyById(update))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("当前租户下组织编码已存在");
    }
  }

  @Test
  void modifyById_throwsWhenParentIsSelf() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization current = org("org1", "Old Name", "HQ", "group");
      current.setTenantId("t1");
      when(repository.findByIdAndTenantId("org1", "t1")).thenReturn(Optional.of(current));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      when(repository.existsByTenantIdAndCodeAndIdNot("t1", "HQ", "org1")).thenReturn(false);

      Organization update = org("org1", "New Name", "HQ", "group");
      update.setParentId("org1"); // self reference
      assertThatThrownBy(() -> service.modifyById(update))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("上级组织不能选择自己");
    }
  }

  @Test
  void modifyById_success() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization current = org("org1", "Old Name", "HQ", "group");
      current.setTenantId("t1");
      when(repository.findByIdAndTenantId("org1", "t1")).thenReturn(Optional.of(current));
      when(dictionaryItemRepository.options(TenantDictionaryCodes.ORGANIZATION_TYPE))
          .thenReturn(List.of(new DictionaryOptionVo("group", "集团")));
      when(repository.existsByTenantIdAndCodeAndIdNot("t1", "HQ", "org1")).thenReturn(false);
      // findById called by super.modifyById (returns empty → skips audit block)
      when(repository.findById("org1")).thenReturn(Optional.empty());
      Organization updated = org("org1", "New Name", "HQ", "group");
      when(repository.updateById(any())).thenAnswer(inv -> inv.getArgument(0));

      Organization result = service.modifyById(updated);

      assertThat(result).isNotNull();
      verify(repository).updateById(any());
    }
  }

  // ── removeByIds ───────────────────────────────────────────────────────────

  @Test
  void removeByIds_returnsEarlyWhenIdsIsNull() {
    service.removeByIds(null);
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void removeByIds_returnsEarlyWhenIdsIsEmpty() {
    service.removeByIds(List.of());
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void removeByIds_returnsEarlyForBlankOnlyIds() {
    service.removeByIds(List.of("  ", ""));
    verify(repository, never()).deleteByIds(any());
  }

  @Test
  void removeByIds_throwsWhenNoTenantInContext() {
    assertThatThrownBy(() -> service.removeByIds(List.of("org1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("请先选择租户");
  }

  @Test
  void removeByIds_throwsWhenOrgNotBelongToTenant() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      when(repository.findAllByIdsAndTenantId(Set.of("org1"), "t1")).thenReturn(Collections.emptyList());
      assertThatThrownBy(() -> service.removeByIds(List.of("org1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("存在无权操作的组织机构或组织机构不存在");
    }
  }

  @Test
  void removeByIds_throwsWhenHasChildOrgs() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization existing = org("org1", "HQ", "HQ", "group");
      when(repository.findAllByIdsAndTenantId(Set.of("org1"), "t1")).thenReturn(List.of(existing));
      when(repository.findIdsByParentIds(Set.of("org1"), "t1")).thenReturn(List.of("child1"));
      assertThatThrownBy(() -> service.removeByIds(List.of("org1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("请先删除子组织机构后再删除当前组织");
    }
  }

  @Test
  void removeByIds_success() {
    try (MockedStatic<AuthorizationContextHolder> mocked = mockStatic(AuthorizationContextHolder.class)) {
      mocked.when(AuthorizationContextHolder::getContext).thenReturn(ctxWithTenant("t1"));
      Organization existing = org("org1", "HQ", "HQ", "group");
      when(repository.findAllByIdsAndTenantId(Set.of("org1"), "t1")).thenReturn(List.of(existing));
      when(repository.findIdsByParentIds(Set.of("org1"), "t1")).thenReturn(Collections.emptyList());
      // super.removeByIds calls findAllByIds; return empty so audit is skipped
      when(repository.findAllByIds(any())).thenReturn(Collections.emptyList());

      service.removeByIds(List.of("org1"));

      verify(repository).deleteByIds(any());
    }
  }
}
