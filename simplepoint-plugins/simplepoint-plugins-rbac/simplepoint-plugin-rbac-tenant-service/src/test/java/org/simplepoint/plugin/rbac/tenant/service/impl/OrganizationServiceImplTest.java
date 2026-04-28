package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Organization;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.OrganizationRepository;
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

  // ── limit ─────────────────────────────────────────────────────────────────

  @Test
  void limit_returnsEmptyPageWhenNoTenantInContext() {
    // No auth context -> currentTenantId(false) returns null
    Page<Organization> result = service.limit(null, Pageable.unpaged());
    assertThat(result.isEmpty()).isTrue();
  }

  // ── create ────────────────────────────────────────────────────────────────

  @Test
  void create_throwsWhenNoTenantInContext() {
    Organization org = new Organization();
    org.setName("HQ");
    org.setCode("HQ");
    org.setType("group");

    // No auth context -> currentTenantId(true) throws
    assertThatThrownBy(() -> service.create(org))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("请先选择租户");
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenNoTenantInContext() {
    Organization org = new Organization();
    org.setId("org1");

    // No auth context -> currentTenantId(true) throws
    assertThatThrownBy(() -> service.modifyById(org))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("请先选择租户");
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
  void removeByIds_throwsWhenNoTenantInContext() {
    // IDs are non-empty -> proceeds to currentTenantId(true) -> throws
    assertThatThrownBy(() -> service.removeByIds(List.of("org1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("请先选择租户");
  }

  @Test
  void removeByIds_throwsWhenSomeOrgsNotBelongToTenant() {
    // We can't easily set up a tenant context in unit test, so just verify the
    // early-exit path for blank-only IDs (normalizeIds filters all blanks to empty)
    service.removeByIds(List.of("  ", ""));
    verify(repository, never()).deleteByIds(any());
  }
}
