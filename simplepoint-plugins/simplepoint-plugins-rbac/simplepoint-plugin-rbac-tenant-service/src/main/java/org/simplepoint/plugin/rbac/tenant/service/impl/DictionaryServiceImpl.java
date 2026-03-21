package org.simplepoint.plugin.rbac.tenant.service.impl;

import java.util.Collection;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dictionary service implementation.
 */
@Service
public class DictionaryServiceImpl extends BaseServiceImpl<DictionaryRepository, Dictionary, String> implements DictionaryService {

  private final DictionaryItemRepository dictionaryItemRepository;

  public DictionaryServiceImpl(
      DictionaryRepository repository,
      DetailsProviderService detailsProviderService,
      DictionaryItemRepository dictionaryItemRepository
  ) {
    super(repository, detailsProviderService);
    this.dictionaryItemRepository = dictionaryItemRepository;
  }

  @Override
  public Collection<DictionaryOptionVo> options(String dictionaryCode) {
    return dictionaryItemRepository.options(requireCode(dictionaryCode, "字典编码不能为空"));
  }

  @Override
  public <S extends Dictionary> S create(S entity) {
    if (entity.getEnabled() == null) {
      entity.setEnabled(true);
    }
    return super.create(entity);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public <S extends Dictionary> Dictionary modifyById(S entity) {
    Dictionary current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("字典不存在"));
    if (entity.getEnabled() == null) {
      entity.setEnabled(current.getEnabled());
    }
    Dictionary updated = (Dictionary) super.modifyById(entity);
    if (!Objects.equals(current.getCode(), updated.getCode())) {
      dictionaryItemRepository.updateDictionaryCode(current.getCode(), updated.getCode());
    }
    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void removeByIds(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      return;
    }
    Set<String> dictionaryCodes = findAllByIds(ids).stream()
        .map(Dictionary::getCode)
        .filter(code -> code != null && !code.isBlank())
        .collect(Collectors.toSet());
    if (!dictionaryCodes.isEmpty()) {
      dictionaryItemRepository.deleteAllByDictionaryCodes(dictionaryCodes);
    }
    super.removeByIds(ids);
  }

  private static String requireCode(String code, String message) {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return code;
  }
}
