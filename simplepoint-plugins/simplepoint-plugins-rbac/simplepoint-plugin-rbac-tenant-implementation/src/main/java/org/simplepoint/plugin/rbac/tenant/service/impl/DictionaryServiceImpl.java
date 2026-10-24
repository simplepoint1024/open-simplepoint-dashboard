package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.Dictionary;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryService;
import org.simplepoint.plugin.rbac.tenant.api.vo.DictionaryOptionVo;
import org.simplepoint.plugin.rbac.tenant.service.initialize.DictionaryRegistrationStore;
import org.simplepoint.plugin.rbac.tenant.service.support.DictionaryCatalogWriteBoundary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dictionary service implementation. */
@Service
public class DictionaryServiceImpl extends BaseServiceImpl<DictionaryRepository, Dictionary, String>
    implements DictionaryService {

  private final DictionaryItemRepository dictionaryItemRepository;
  private final DictionaryCatalogWriteBoundary catalogWrites;
  private final DictionaryRegistrationStore registrations;

  /** Dictionary Service Impl. */
  public DictionaryServiceImpl(
      DictionaryRepository repository,
      DetailsProviderService detailsProviderService,
      DictionaryItemRepository dictionaryItemRepository,
      DictionaryCatalogWriteBoundary catalogWrites,
      DictionaryRegistrationStore registrations) {
    super(repository, detailsProviderService);
    this.dictionaryItemRepository = dictionaryItemRepository;
    this.catalogWrites = catalogWrites;
    this.registrations = registrations;
  }

  @Override
  protected boolean isDataScopeApplicable() {
    return false;
  }

  @Override
  public Collection<DictionaryOptionVo> options(String dictionaryCode) {
    return dictionaryItemRepository.options(requireCode(dictionaryCode, "字典编码不能为空"));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Dictionary> S create(S entity) {
    catalogWrites.enter();
    registrations.requireCreateAllowed(entity.getCode());
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    return super.create(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<Dictionary> create(Collection<Dictionary> entities) {
    catalogWrites.enter();
    return entities.stream().map(this::create).toList();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Dictionary> Dictionary modifyById(S entity) {
    catalogWrites.enter();
    Dictionary current =
        findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("字典不存在"));
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    String previousCode = current.getCode();
    registrations.requireRenameAllowed(
        previousCode, entity.getCode() == null ? previousCode : entity.getCode());
    Dictionary updated = (Dictionary) super.modifyById(entity);
    if (!Objects.equals(previousCode, updated.getCode())) {
      dictionaryItemRepository.updateDictionaryCode(previousCode, updated.getCode());
    }
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    catalogWrites.enter();
    Set<String> dictionaryCodes =
        findAllByIds(ids).stream()
            .map(Dictionary::getCode)
            .filter(code -> code != null && !code.isBlank())
            .collect(Collectors.toSet());
    registrations.requireRemovalAllowed(dictionaryCodes);
    if (!dictionaryCodes.isEmpty()) {
      dictionaryItemRepository.deleteAllByDictionaryCodes(dictionaryCodes);
    }
    super.removeByIds(ids);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeById(String id) {
    removeByIds(Set.of(id));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeAll() {
    catalogWrites.enter();
    removeByIds(findAll(java.util.Map.of()).stream().map(Dictionary::getId).toList());
  }

  private static String requireCode(String code, String message) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return code;
  }
}
