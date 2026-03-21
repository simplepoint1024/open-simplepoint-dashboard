package org.simplepoint.plugin.rbac.tenant.service.impl;

import org.simplepoint.api.security.service.DetailsProviderService;
import org.simplepoint.core.base.service.impl.BaseServiceImpl;
import org.simplepoint.plugin.rbac.tenant.api.entity.DictionaryItem;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryItemRepository;
import org.simplepoint.plugin.rbac.tenant.api.repository.DictionaryRepository;
import org.simplepoint.plugin.rbac.tenant.api.service.DictionaryItemService;
import org.springframework.stereotype.Service;

/**
 * Dictionary item service implementation.
 */
@Service
public class DictionaryItemServiceImpl extends BaseServiceImpl<DictionaryItemRepository, DictionaryItem, String>
    implements DictionaryItemService {

  private final DictionaryRepository dictionaryRepository;

  public DictionaryItemServiceImpl(
      DictionaryItemRepository repository,
      DetailsProviderService detailsProviderService,
      DictionaryRepository dictionaryRepository
  ) {
    super(repository, detailsProviderService);
    this.dictionaryRepository = dictionaryRepository;
  }

  @Override
  public <S extends DictionaryItem> S create(S entity) {
    normalizeEntity(entity, null);
    return super.create(entity);
  }

  @Override
  public <S extends DictionaryItem> DictionaryItem modifyById(S entity) {
    DictionaryItem current = findById(entity.getId()).orElseThrow(() -> new IllegalArgumentException("字典项不存在"));
    normalizeEntity(entity, current);
    return super.modifyById(entity);
  }

  private void normalizeEntity(DictionaryItem entity, DictionaryItem current) {
    if (entity.getDictionaryCode() == null || entity.getDictionaryCode().isBlank()) {
      if (current != null) {
        entity.setDictionaryCode(current.getDictionaryCode());
      }
    }
    if (entity.getDictionaryCode() == null || entity.getDictionaryCode().isBlank()) {
      throw new IllegalArgumentException("字典编码不能为空");
    }
    if (!dictionaryRepository.existsByCode(entity.getDictionaryCode())) {
      throw new IllegalArgumentException("所属字典不存在");
    }
    if (entity.getEnabled() == null) {
      entity.setEnabled(current == null ? Boolean.TRUE : current.getEnabled());
    }
  }
}
