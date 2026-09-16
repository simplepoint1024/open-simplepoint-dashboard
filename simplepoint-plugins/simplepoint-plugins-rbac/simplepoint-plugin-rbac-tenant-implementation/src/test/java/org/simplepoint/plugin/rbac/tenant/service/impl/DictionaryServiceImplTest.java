package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplepoint.api.base.audit.ModifyDataAuditingService;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryRepository;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplTest {

  @Mock
  DictionaryRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  DictionaryItemRepository dictionaryItemRepository;

  @InjectMocks
  DictionaryServiceImpl service;

  @BeforeEach
  void stubAuditingService() {
    lenient().when(detailsProviderService.getDialects(ModifyDataAuditingService.class))
        .thenReturn(Set.of());
  }

  // ── options ──────────────────────────────────────────────────────────────

  @Test
  void options_throwsWhenCodeIsNull() {
    assertThatThrownBy(() -> service.options(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典编码不能为空");
  }

  @Test
  void options_throwsWhenCodeIsBlank() {
    assertThatThrownBy(() -> service.options("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典编码不能为空");
  }

  @Test
  void options_delegatesToItemRepository() {
    List<DictionaryOptionVo> expected = List.of(new DictionaryOptionVo("v1", "Label1"));
    when(dictionaryItemRepository.options("dict.code")).thenReturn(expected);

    Collection<DictionaryOptionVo> result = service.options("dict.code");

    assertThat(result).isEqualTo(expected);
    verify(dictionaryItemRepository).options("dict.code");
  }

  // ── create ───────────────────────────────────────────────────────────────

  @Test
  void create_setsEnabledTrueWhenNull() {
    Dictionary dict = new Dictionary();
    dict.setId("d1");
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Dictionary result = service.create(dict);

    assertThat(result.getEnabled()).isTrue();
  }

  @Test
  void create_keepsExistingEnabledFalse() {
    Dictionary dict = new Dictionary();
    dict.setId("d2");
    dict.setEnabled(false);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Dictionary result = service.create(dict);

    assertThat(result.getEnabled()).isFalse();
  }

  @Test
  void create_keepsExistingEnabledTrue() {
    Dictionary dict = new Dictionary();
    dict.setId("d3");
    dict.setEnabled(true);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Dictionary result = service.create(dict);

    assertThat(result.getEnabled()).isTrue();
  }

  // ── modifyById ───────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenDictionaryNotFound() {
    Dictionary entity = new Dictionary();
    entity.setId("missing");
    when(repository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典不存在");
  }

  // ── removeByIds ──────────────────────────────────────────────────────────

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
  void removeByIds_deletesDictionaryItemsBeforeDictionaries() {
    Dictionary dict = new Dictionary();
    dict.setId("d1");
    dict.setCode("code1");
    when(repository.findAllByIds(any())).thenReturn(List.of(dict));

    service.removeByIds(List.of("d1"));

    verify(dictionaryItemRepository).deleteAllByDictionaryCodes(Set.of("code1"));
    verify(repository).deleteByIds(any());
  }
}
