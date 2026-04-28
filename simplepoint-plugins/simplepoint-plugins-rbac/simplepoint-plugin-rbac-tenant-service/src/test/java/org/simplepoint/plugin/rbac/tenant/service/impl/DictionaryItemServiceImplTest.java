package org.simplepoint.plugin.rbac.tenant.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

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
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryRepository;

@ExtendWith(MockitoExtension.class)
class DictionaryItemServiceImplTest {

  @Mock
  DictionaryItemRepository repository;

  @Mock
  DetailsProviderService detailsProviderService;

  @Mock
  DictionaryRepository dictionaryRepository;

  @InjectMocks
  DictionaryItemServiceImpl service;

  @BeforeEach
  void stubAuditingService() {
    lenient().when(detailsProviderService.getDialects(ModifyDataAuditingService.class))
        .thenReturn(Set.of());
  }

  // ── create: dictionaryCode validation ────────────────────────────────────

  @Test
  void create_throwsWhenDictionaryCodeIsNull() {
    DictionaryItem item = itemWithCodeNameValue(null, "ItemName", "v1");
    assertThatThrownBy(() -> service.create(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典编码不能为空");
  }

  @Test
  void create_throwsWhenDictionaryCodeIsBlank() {
    DictionaryItem item = itemWithCodeNameValue("  ", "ItemName", "v1");
    assertThatThrownBy(() -> service.create(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典编码不能为空");
  }

  // ── create: name validation ───────────────────────────────────────────────

  @Test
  void create_throwsWhenNameIsNull() {
    // name check happens before dictionary-existence check; no stub needed
    DictionaryItem item = itemWithCodeNameValue("dict.code", null, "v1");

    assertThatThrownBy(() -> service.create(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典项名称不能为空");
  }

  // ── create: value validation ──────────────────────────────────────────────

  @Test
  void create_throwsWhenValueIsNull() {
    // value check happens before dictionary-existence check; no stub needed
    DictionaryItem item = itemWithCodeNameValue("dict.code", "ItemName", null);

    assertThatThrownBy(() -> service.create(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典项值不能为空");
  }

  // ── create: dictionary existence ─────────────────────────────────────────

  @Test
  void create_throwsWhenDictionaryDoesNotExist() {
    DictionaryItem item = itemWithCodeNameValue("nonexistent", "ItemName", "v1");
    when(dictionaryRepository.existsByCode("nonexistent")).thenReturn(false);

    assertThatThrownBy(() -> service.create(item))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("所属字典不存在");
  }

  // ── create: enabled default ───────────────────────────────────────────────

  @Test
  void create_setsEnabledTrueWhenNullAndNoCurrent() {
    DictionaryItem item = itemWithCodeNameValue("dict.code", "ItemName", "v1");
    item.setEnabled(null);
    when(dictionaryRepository.existsByCode("dict.code")).thenReturn(true);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    DictionaryItem result = service.create(item);

    assertThat(result.getEnabled()).isTrue();
  }

  @Test
  void create_keepsExplicitEnabledFalse() {
    DictionaryItem item = itemWithCodeNameValue("dict.code", "ItemName", "v1");
    item.setEnabled(false);
    when(dictionaryRepository.existsByCode("dict.code")).thenReturn(true);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    DictionaryItem result = service.create(item);

    assertThat(result.getEnabled()).isFalse();
  }

  // ── modifyById ────────────────────────────────────────────────────────────

  @Test
  void modifyById_throwsWhenItemNotFound() {
    DictionaryItem entity = new DictionaryItem();
    entity.setId("missing");
    when(repository.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("字典项不存在");
  }

  @Test
  void modifyById_throwsDictionaryNotExistWhenCodeInheritedFromCurrentIsInvalid() {
    // When entity has no dictionaryCode the code is inherited from current,
    // and then the dictionary-existence check fires — avoiding super.modifyById()
    DictionaryItem current = itemWithCodeNameValue("inherited.code", "OldName", "v1");
    current.setId("item1");
    current.setEnabled(true);

    DictionaryItem entity = new DictionaryItem();
    entity.setId("item1");
    entity.setDictionaryCode(null);
    entity.setName("NewName");
    entity.setValue("v2");

    when(repository.findById("item1")).thenReturn(Optional.of(current));
    when(dictionaryRepository.existsByCode("inherited.code")).thenReturn(false);

    assertThatThrownBy(() -> service.modifyById(entity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("所属字典不存在");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static DictionaryItem itemWithCodeNameValue(String code, String name, String value) {
    DictionaryItem item = new DictionaryItem();
    item.setDictionaryCode(code);
    item.setName(name);
    item.setValue(value);
    return item;
  }
}
